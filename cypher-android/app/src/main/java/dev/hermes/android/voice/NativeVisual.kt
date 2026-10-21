package dev.hermes.android.voice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Independent one-frame-per-second visual input; no peer connection or network transport. */
internal class NativeVisual(private val context:Context,private val handler:Handler,private val permitted:()->Boolean,
    private val frame:(ByteArray,Bitmap?,()->Boolean)->Unit,private val failed:(String)->Unit,private val stopped:()->Unit) {
    private var device:CameraDevice?=null;private var session:CameraCaptureSession?=null
    private var reader:ImageReader?=null;private var projection:MediaProjection?=null;private var display:VirtualDisplay?=null
    private var cameraClosed:CompletableDeferred<Unit>?=null;private var projectionClosed:CompletableDeferred<Unit>?=null
    @Volatile private var generation=0L;@Volatile private var lastFrame=0L
    private var watchdog:Runnable?=null
    @Volatile private var failureReported=false
    @Synchronized private fun fail(epoch:Long,message:String) {
        if(epoch!=generation||failureReported) return
        failureReported=true;active=false;cancelWatchdog();failed(message)
    }
    private fun cancelWatchdog() { watchdog?.let { handler.removeCallbacks(it) };watchdog=null }
    private fun startupFailed(epoch:Long) { if(epoch==generation) { failureReported=true;active=false;cancelWatchdog() } }
    private var previewSurface:android.view.Surface?=null
    @Volatile private var previewView:android.view.TextureView?=null
    private var configureCamera:(()->Unit)?=null
    private var previewTransform:(()->Unit)?=null
    private val rotationListener=object:DisplayManager.DisplayListener {
        override fun onDisplayAdded(id:Int)=Unit
        override fun onDisplayRemoved(id:Int)=Unit
        override fun onDisplayChanged(id:Int) { previewTransform?.invoke() }
    }
    fun preview(view:android.view.TextureView,attached:Boolean) {
        handler.post {
            if(!attached&&previewView!==view) return@post
            previewView=if(attached) view else null
            configureCamera?.invoke()
        }
    }
    @Volatile var active=false;private set
    private fun accept() = !failureReported&&permitted()&&(lastFrame==0L||SystemClock.elapsedRealtime()-lastFrame>=1000)
    private fun watch(epoch:Long) {
        val started=SystemClock.elapsedRealtime()
        val task=object:Runnable {
            override fun run() {
                if(epoch!=generation||failureReported) return
                if(permitted()&&SystemClock.elapsedRealtime()-(lastFrame.takeIf { it>0 }?:started)>=8000) {
                    fail(epoch,"No visual frame arrived from the capture device");return
                }
                handler.postDelayed(this,1000)
            }
        }
        watchdog=task;handler.postDelayed(task,1000)
    }
    private fun displayDegrees():Int = when(context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.rotation) {
        android.view.Surface.ROTATION_90 -> 90;android.view.Surface.ROTATION_180 -> 180;android.view.Surface.ROTATION_270 -> 270;else -> 0
    }
    private fun delivered(epoch:Long,bitmap:Bitmap,preview:Boolean) {
        var transferred=false
        try {
            val bytes=boundedVisualJpeg(bitmap)
            if(epoch!=generation||failureReported||!permitted()) return
            lastFrame=SystemClock.elapsedRealtime();active=true
            frame(bytes,if(preview) bitmap else null) { epoch==generation&&!failureReported&&permitted() }
            transferred=preview
        } finally { if(!transferred&&!bitmap.isRecycled) bitmap.recycle() }
    }
    @Suppress("MissingPermission")
    suspend fun camera(rear:Boolean) {
        stop();val epoch=generation;lastFrame=0
        val manager=context.getSystemService(CameraManager::class.java)
        val id=manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==if(rear) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT } ?: error("Camera unavailable")
        val characteristics=manager.getCameraCharacteristics(id)
        val configuration=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?:error("Camera stream configuration unavailable")
        val sizes=configuration.getOutputSizes(ImageFormat.YUV_420_888)?.takeIf { it.isNotEmpty() }?:error("Camera YUV output unavailable")
        val previewSizes=configuration.getOutputSizes(android.graphics.SurfaceTexture::class.java).orEmpty().toSet()
        val compatible=sizes.filter { it in previewSizes };check(compatible.isNotEmpty()) { "Camera preview output unavailable" }
        val size=compatible.filter { maxOf(it.width,it.height)<=640 }.maxByOrNull { it.width*it.height } ?: compatible.minBy { it.width.toLong()*it.height }
        val images=ImageReader.newInstance(size.width,size.height,ImageFormat.YUV_420_888,2);reader=images
        val closedSignal=CompletableDeferred<Unit>();cameraClosed=closedSignal
        images.setOnImageAvailableListener({ source ->
            if(epoch==generation) try {
                source.acquireLatestImage()?.use { image ->
                    if(epoch!=generation||!accept()) return@use
                    val crop=image.cropRect
                    val planes=image.planes.map { CameraPlane(it.buffer,it.rowStride,it.pixelStride) }
                    val nv21=cameraNv21(crop.width(),crop.height(),planes,crop.left,crop.top)
                    val stream=ByteArrayOutputStream()
                    check(android.graphics.YuvImage(nv21,ImageFormat.NV21,crop.width(),crop.height(),null)
                        .compressToJpeg(android.graphics.Rect(0,0,crop.width(),crop.height()),80,stream)) { "Camera frame encoding failed" }
                    val bytes=stream.toByteArray()
                    val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:error("Camera frame decoding failed")
                    val degrees=cameraFrameRotation(characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)?:0,displayDegrees(),!rear)
                    val bitmap=transformVisualBitmap(decoded,degrees)
                    delivered(epoch,bitmap,true)
                }
            } catch(error:Exception) { fail(epoch,error.message?:"Camera frame unavailable") }
        },handler)
        watch(epoch)
        try { manager.openCamera(id,object:CameraDevice.StateCallback() {
            override fun onOpened(camera:CameraDevice) {
                if(epoch!=generation||failureReported||!permitted()) { camera.close();return };device=camera
                var configurationRevision=0L
                configureCamera={
                    val revision=++configurationRevision
                    if(epoch==generation&&!failureReported&&permitted()) try {
                        session?.close();session=null
                        previewSurface?.release();previewSurface=null
                        val view=previewView
                        val texture=view?.surfaceTexture
                        texture?.setDefaultBufferSize(size.width,size.height)
                        val target=texture?.let { android.view.Surface(it) };previewSurface=target
                        previewTransform={ view?.post {
                            if(epoch==generation&&view===previewView&&view.isAvailable&&view.width>0&&view.height>0) {
                                val degrees=cameraFrameRotation(characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)?:0,displayDegrees(),!rear)
                                view.setTransform(cameraPreviewMatrix(size.width,size.height,view.width,view.height,degrees,!rear,characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)?:0))
                            }
                        };Unit }
                        previewTransform?.invoke()
                        val targets=listOfNotNull(images.surface,target)
                        camera.createCaptureSession(targets,object:CameraCaptureSession.StateCallback() {
                            override fun onConfigured(value:CameraCaptureSession) {
                                if(epoch!=generation||revision!=configurationRevision||failureReported||!permitted()||target!==previewSurface) { value.close();return };session=value
                                try {
                                    val request=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { targets.forEach { addTarget(it) } }.build()
                                    value.setRepeatingRequest(request,null,handler)
                                } catch(error:Exception) { value.close();if(revision==configurationRevision&&target===previewSurface) fail(epoch,"Camera capture unavailable") }
                            }
                            override fun onConfigureFailed(value:CameraCaptureSession) { value.close();if(revision==configurationRevision&&target===previewSurface) fail(epoch,"Camera capture unavailable") }
                        },handler)
                    } catch(error:Exception) { fail(epoch,"Camera capture unavailable") }
                }
                context.getSystemService(DisplayManager::class.java).registerDisplayListener(rotationListener,handler)
                configureCamera?.invoke()
            }
            override fun onDisconnected(camera:CameraDevice) { camera.close();fail(epoch,"Camera disconnected") }
            override fun onError(camera:CameraDevice,error:Int) { camera.close();fail(epoch,"Camera device error $error") }
            override fun onClosed(camera:CameraDevice) { closedSignal.complete(Unit) }
        },handler) } catch(error:Exception) { startupFailed(epoch);cameraClosed=null;images.close();reader=null;throw error }
    }
    suspend fun screen(consent:Intent) {
        stop();val epoch=generation;lastFrame=0
        val capture=checkNotNull(context.getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK,consent));projection=capture
        val closedSignal=CompletableDeferred<Unit>();projectionClosed=closedSignal
        capture.registerCallback(object:MediaProjection.Callback() { override fun onStop() { closedSignal.complete(Unit);if(epoch==generation) stopped() } },handler)
        val images=ImageReader.newInstance(640,960,android.graphics.PixelFormat.RGBA_8888,2);reader=images
        images.setOnImageAvailableListener({ source ->
            if(epoch==generation) try {
                source.acquireLatestImage()?.use { image ->
                    if(epoch!=generation||!accept()) return@use
                    val plane=image.planes[0]
                    val padded=Bitmap.createBitmap(plane.rowStride/plane.pixelStride,image.height,Bitmap.Config.ARGB_8888)
                    try {
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val cropped=Bitmap.createBitmap(padded,0,0,image.width,image.height)
                        if(cropped!==padded) padded.recycle()
                        delivered(epoch,transformVisualBitmap(cropped,0),false)
                    } finally { if(!padded.isRecycled) padded.recycle() }
                }
            } catch(error:Exception) { fail(epoch,error.message?:"Screen frame unavailable") }
        },handler)
        watch(epoch)
        try { display=capture.createVirtualDisplay("Beacon screen",640,960,context.resources.displayMetrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,images.surface,null,handler) }
        catch(error:Exception) { startupFailed(epoch);throw error }
    }
    suspend fun stop() {
        generation++;active=false;lastFrame=0;failureReported=false;configureCamera=null;previewTransform=null
        context.getSystemService(DisplayManager::class.java).unregisterDisplayListener(rotationListener)
        cancelWatchdog()
        val errors=listOf(
            runCatching { session?.close();session=null }.exceptionOrNull(),runCatching { previewSurface?.release();previewSurface=null }.exceptionOrNull(),runCatching { device?.close();device=null }.exceptionOrNull(),
            runCatching { display?.release();display=null }.exceptionOrNull(),runCatching { projection?.stop();projection=null }.exceptionOrNull(),runCatching { reader?.close();reader=null }.exceptionOrNull()
        ).filterNotNull()
        val confirmations=listOf(
            confirmation { cameraClosed?.let { withTimeout(5000) { it.await() } };cameraClosed=null },
            confirmation { projectionClosed?.let { withTimeout(5000) { it.await() } };projectionClosed=null }
        ).filterNotNull()
        if(errors.isNotEmpty()) throw IllegalStateException("Visual capture release unconfirmed",errors.first())
        if(confirmations.isNotEmpty()) throw IllegalStateException("Visual capture close callback unconfirmed",confirmations.first())
    }
    private suspend fun confirmation(block:suspend ()->Unit):Throwable? = try { block();null }
        catch(error:kotlinx.coroutines.TimeoutCancellationException) { currentCoroutineContext().ensureActive();error }
        catch(error:CancellationException) { throw error }
        catch(error:Exception) { error }
}


internal data class CameraPlane(val buffer:java.nio.ByteBuffer,val rowStride:Int,val pixelStride:Int)

/** Plane buffers may include row padding, interleaved chroma and a nonzero buffer position. */
internal fun cameraNv21(width:Int,height:Int,planes:List<CameraPlane>,left:Int=0,top:Int=0):ByteArray {
    require(width>0&&height>0&&width%2==0&&height%2==0&&left>=0&&top>=0&&left%2==0&&top%2==0&&planes.size==3)
    val size=Math.multiplyExact(width,height)
    val out=ByteArray(Math.addExact(size,size/2))
    fun copy(index:Int,w:Int,h:Int,start:Int,step:Int,x:Int,y:Int) {
        val plane=planes[index];require(plane.rowStride>0&&plane.pixelStride>0)
        val buffer=plane.buffer.duplicate();val base=buffer.position()
        for(row in 0 until h) for(col in 0 until w) {
            val offset=base+(row+y)*plane.rowStride+(col+x)*plane.pixelStride
            require(offset>=base&&offset<buffer.limit()) { "Camera plane is truncated" }
            out[start+(row*w+col)*step]=buffer.get(offset)
        }
    }
    copy(0,width,height,0,1,left,top)
    copy(2,width/2,height/2,size,2,left/2,top/2)
    copy(1,width/2,height/2,size+1,2,left/2,top/2)
    return out
}
internal fun cameraFrameRotation(sensor:Int,display:Int,front:Boolean):Int {
    require(sensor in setOf(0,90,180,270)&&display in setOf(0,90,180,270))
    return (sensor-display*(if(front) 1 else -1)+360)%360
}
internal fun transformVisualBitmap(input:Bitmap,degrees:Int):Bitmap {
    var result=input
    val longest=maxOf(input.width,input.height)
    if(longest>640) {
        result=Bitmap.createScaledBitmap(input,(input.width*640/longest).coerceAtLeast(1),(input.height*640/longest).coerceAtLeast(1),true)
        if(result!==input) input.recycle()
    }
    if(degrees!=0) {
        val rotated=Bitmap.createBitmap(result,0,0,result.width,result.height,android.graphics.Matrix().apply { postRotate(degrees.toFloat()) },true)
        if(rotated!==result) result.recycle()
        result=rotated
    }
    return result
}
internal fun boundedVisualJpeg(bitmap:Bitmap):ByteArray {
    for(quality in intArrayOf(65,45,25,10)) {
        val stream=ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG,quality,stream)) { "Visual frame encoding failed" }
        val bytes=stream.toByteArray()
        if(bytes.size<=96000) return bytes
    }
    error("Visual frame exceeds the safe upload limit")
}

/** TextureView already applies sensor orientation; transform only layout, display rotation and selfie mirroring. */
internal fun cameraPreviewMatrix(bufferWidth:Int,bufferHeight:Int,width:Int,height:Int,degrees:Int,front:Boolean,sensor:Int=90):android.graphics.Matrix {
    require(bufferWidth>0&&bufferHeight>0&&width>0&&height>0)
    val naturalWidth=if(sensor%180!=0) bufferHeight else bufferWidth
    val naturalHeight=if(sensor%180!=0) bufferWidth else bufferHeight
    val source=floatArrayOf(-naturalWidth/2f,-naturalHeight/2f,naturalWidth/2f,-naturalHeight/2f,-naturalWidth/2f,naturalHeight/2f)
    val display=(if(front) sensor-degrees else degrees-sensor)+360
    android.graphics.Matrix().apply { postRotate(-display.toFloat()) }.mapPoints(source)
    val swapped=degrees%180!=0
    val scale=maxOf(width.toFloat()/(if(swapped) bufferHeight else bufferWidth),height.toFloat()/(if(swapped) bufferWidth else bufferHeight))
    for(i in 0..2) { source[i*2]=source[i*2]*scale*(if(front) -1 else 1)+width/2f;source[i*2+1]=source[i*2+1]*scale+height/2f }
    return android.graphics.Matrix().apply { setPolyToPoly(floatArrayOf(0f,0f,width.toFloat(),0f,0f,height.toFloat()),0,source,0,3) }
}
