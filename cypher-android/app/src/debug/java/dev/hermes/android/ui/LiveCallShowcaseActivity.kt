package dev.hermes.android.ui

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.hermes.android.CypherTheme
import dev.hermes.android.core.Scope
import dev.hermes.android.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Non-exported DEBUG-only host of production composables/windows. No provider, auth or SDK call. */
class LiveCallShowcaseActivity : ComponentActivity() {
    companion object {
        val actor=Scope("https://synthetic.invalid","mock-owner","default")
        val id="a".repeat(32)
        private fun initialCall() = CallState(active=true,state="speaking",remoteSpeaking=true,remoteAudioLevel=.65f,callId=id,conversationScope=actor.session("mock-chat"),transcriptStatus="unavailable",
            transcripts=listOf(CallTranscript("$id:user:1","user","Could you explain that while I keep working?",false),CallTranscript("$id:assistant:1","assistant","Yes. Your conversation stays in the same chat while this call is minimized.",false)))
        val source=MutableStateFlow(initialCall())
        var ownerAllowed=true
        var reduced=false
        private var overlay: CallOverlayWindow?=null
        const val mediaStarts=1 // Explicitly mocked; never a native connection claim.
    }
    private var hidden=false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) { overlay?.close(); overlay = null; source.value = initialCall(); ownerAllowed = true; reduced = false }
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { CypherTheme(dark=true) {
            val call by source.collectAsState()
            var tab by remember { mutableStateOf("Cypher") }
            val drawer=rememberDrawerState(DrawerValue.Closed)
            val scope=rememberCoroutineScope()
            BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
            AdaptiveCallNavigation(drawer, showNavigation = true, navigation = {_ ->
                Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
                    Text("Cypher",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(12.dp))
                    listOf("Chat","Cypher","Tasks","Spaces","Settings & more").forEach { item -> NavigationDrawerItem(label={Text(item)},selected=tab==item,onClick={tab=item},modifier=Modifier.testTag("mock-nav-$item")) }
                    Text("Synthetic chat · unchanged",Modifier.padding(12.dp))
                }
            }) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        if(!LocalPersistentNavigation.current) IconButton({scope.launch{drawer.open()}},modifier=Modifier.testTag("mock-open-menu")) { Icon(Icons.Rounded.Menu,"Open navigation menu") }
                        Text("Mock media/provider · synthetic UI evidence",style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f).padding(8.dp))
                    }
                    if(call.active) LiveCallSurface(call,CallUiActions({source.value=source.value.copy(muted=!source.value.muted)},
                        {source.value=source.value.copy(cameraSharing=!source.value.cameraSharing,cameraCanFlip=true)},
                        {source.value=source.value.copy(cameraRear=!source.value.cameraRear)}, {startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:$packageName")))},
                        {finishCall()},{}),!reduced,Modifier.weight(1f),onConversation={tab="Chat"}) { camera ->
                        Box(camera.background(if(call.cameraRear) Color(0xff163f56) else Color(0xff263858))) {
                            Text("Mock camera · no sensor/provider",color=Color.White,modifier=Modifier.padding(32.dp))
                        }
                    } else Text("Synthetic call ended",Modifier.padding(32.dp).testTag("mock-call-ended"))
                }
            }
        } }

    }
    private fun showBubble() {
        overlay?.close()
        overlay=CallOverlayWindow(this,{source.value},{hidden&&overlayOwnsCall(source.value,id,actor,ownerAllowed,
            !getSystemService(KeyguardManager::class.java).isDeviceLocked&&getSystemService(PowerManager::class.java).isInteractive)},
            {source.value=source.value.copy(muted=!source.value.muted)},{finishCall()},
            {startActivity(Intent(this,LiveCallShowcaseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))},{reduced},secure=false)
    }
    fun changeOwner(allowed: Boolean) { ownerAllowed=allowed;overlay?.refresh() }
    fun motion(reducedMotion: Boolean) { reduced=reducedMotion;source.value=source.value.copy(error=if(reducedMotion) "Reduced motion · mock media" else "") }
    fun finishCall() { source.value=source.value.copy(active=false,minimized=false,cameraSharing=false);overlay?.close();overlay=null }
    override fun onResume() { super.onResume();hidden=false;source.value=source.value.withOverlayPermission(android.provider.Settings.canDrawOverlays(this));overlay?.refresh();source.value=source.value.copy(minimized=false) }
    override fun onStop() { hidden=true;source.value=source.value.copy(cameraSharing=false).withOverlayPermission(android.provider.Settings.canDrawOverlays(this));if(!isChangingConfigurations && backgroundNeedsBubble(source.value,false)) { source.value=source.value.copy(minimized=true);showBubble() };overlay?.refresh();super.onStop() }
    override fun onDestroy() { overlay?.close();overlay=null;super.onDestroy() }
}
