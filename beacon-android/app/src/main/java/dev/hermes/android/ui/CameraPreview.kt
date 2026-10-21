package dev.hermes.android.ui

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.hermes.android.voice.CallController

/** Camera2 renders directly into this surface; provider uploads stay independently sampled. */
@Composable
fun CameraPreview(calls:CallController,callId:String,modifier:Modifier=Modifier) {
    key(calls,callId) {
        AndroidView(modifier=modifier,factory={ context ->
            TextureView(context).apply {
                surfaceTextureListener=object:TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture:SurfaceTexture,width:Int,height:Int) { calls.cameraPreview(callId,this@apply,true) }
                    override fun onSurfaceTextureSizeChanged(texture:SurfaceTexture,width:Int,height:Int) { calls.cameraPreview(callId,this@apply,true) }
                    override fun onSurfaceTextureDestroyed(texture:SurfaceTexture):Boolean { calls.cameraPreview(callId,this@apply,false);return true }
                    override fun onSurfaceTextureUpdated(texture:SurfaceTexture) = Unit
                }
            }
        },onRelease={ calls.cameraPreview(callId,it,false) })
    }
}
