package dev.hermes.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hermes.android.voice.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeVisualDeviceTest {
    @get:org.junit.Rule val foreground=androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun failedGenerationStopsFrameRetriesAndCancelsItsWatchdog() = kotlinx.coroutines.runBlocking {
        val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val reports=java.util.concurrent.atomic.AtomicInteger()
        val capture=NativeVisual(context,android.os.Handler(android.os.Looper.getMainLooper()),{true},{_,bitmap,_->bitmap?.recycle()},{reports.incrementAndGet()},{})
        val fail=NativeVisual::class.java.getDeclaredMethod("fail",java.lang.Long.TYPE,String::class.java).apply { isAccessible=true }
        val watch=NativeVisual::class.java.getDeclaredMethod("watch",java.lang.Long.TYPE).apply { isAccessible=true }
        val accept=NativeVisual::class.java.getDeclaredMethod("accept").apply { isAccessible=true }
        val startupFailed=NativeVisual::class.java.getDeclaredMethod("startupFailed",java.lang.Long.TYPE).apply { isAccessible=true }
        val watchdog=NativeVisual::class.java.getDeclaredField("watchdog").apply { isAccessible=true }
        try {
            watch.invoke(capture,0L);assertNotNull(watchdog.get(capture))
            repeat(60) { fail.invoke(capture,0L,"Synthetic encoding failure") }
            assertEquals(1,reports.get());assertEquals(false,accept.invoke(capture));assertNull(watchdog.get(capture))
            capture.stop();assertEquals(true,accept.invoke(capture))
            fail.invoke(capture,0L,"Stale generation");assertEquals(1,reports.get())
            watch.invoke(capture,1L);startupFailed.invoke(capture,1L)
            assertNull(watchdog.get(capture));assertEquals(false,accept.invoke(capture));assertEquals(1,reports.get())
            fail.invoke(capture,1L,"Duplicate startup failure");assertEquals(1,reports.get())
        } finally { capture.stop() }
    }
    @Test fun cameraSurfaceDeliversManyPreviewFramesPerProviderSampleAndCloses() = kotlinx.coroutines.runBlocking {
        val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        check(context.packageName.endsWith(".debug"))
        check(context.checkSelfPermission(android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED)
        val thread=android.os.HandlerThread("Camera preview test").apply { start() }
        val frames=java.util.concurrent.atomic.AtomicInteger();val samples=java.util.concurrent.atomic.AtomicInteger()
        val faults=java.util.concurrent.ConcurrentLinkedQueue<String>()
        val permitted=java.util.concurrent.atomic.AtomicBoolean(true)
        val capture=NativeVisual(context,android.os.Handler(thread.looper),{permitted.get()},
            {_,bitmap,_ -> samples.incrementAndGet();bitmap?.recycle() },{faults.add(it)},{})
        val view=java.util.concurrent.atomic.AtomicReference<android.view.TextureView>()
        try {
            foreground.setContent {
                androidx.compose.ui.viewinterop.AndroidView(modifier=androidx.compose.ui.Modifier.fillMaxSize(),factory={ ctx -> android.view.TextureView(ctx).also { texture ->
                    view.set(texture)
                    texture.surfaceTextureListener=object:android.view.TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface:android.graphics.SurfaceTexture,w:Int,h:Int) { capture.preview(texture,true) }
                        override fun onSurfaceTextureSizeChanged(surface:android.graphics.SurfaceTexture,w:Int,h:Int) { capture.preview(texture,true) }
                        override fun onSurfaceTextureDestroyed(surface:android.graphics.SurfaceTexture):Boolean { capture.preview(texture,false);return true }
                        override fun onSurfaceTextureUpdated(surface:android.graphics.SurfaceTexture) { frames.incrementAndGet() }
                    }
                } })
            }
            foreground.waitUntil(5000) { view.get()?.isAvailable==true }
            for(rear in listOf(false,true)) {
                val startedFrames=frames.get()
                capture.camera(rear)
                foreground.waitUntil(8000) { frames.get()>=startedFrames+3 }
                val before=frames.get();val sampled=samples.get();val started=android.os.SystemClock.elapsedRealtime()
                kotlinx.coroutines.delay(2500)
                val elapsed=android.os.SystemClock.elapsedRealtime()-started
                val delivered=frames.get()-before;val uploads=samples.get()-sampled
                val fps=delivered*1000.0/elapsed
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply {
                    putString("camera_facing",if(rear) "rear" else "front");putInt("preview_frames",delivered);putInt("provider_samples",uploads)
                    putLong("observation_ms",elapsed);putDouble("preview_fps",fps)
                })
                assertTrue("Camera preview must run independently: preview=$delivered samples=$uploads fps=$fps faults=$faults",fps>=15&&delivered>uploads*3)
                assertTrue("Provider sampling exceeded one frame per second",uploads in 1..3)
                assertTrue(faults.toString(),faults.isEmpty())
                capture.stop()
                kotlinx.coroutines.delay(300)
                val stoppedFrames=frames.get();kotlinx.coroutines.delay(400)
                assertEquals("Stopped camera still renders",stoppedFrames,frames.get())
            }
            permitted.set(false);capture.stop()
            assertFalse(capture.active)
        } finally {
            permitted.set(false)
            try { capture.stop() } finally { thread.quitSafely();thread.join(2000) }
        }
    }

    @Test fun nativePreviewTransformCentersRotatesAndMirrors() {
        for(rotation in listOf(0,90,180,270)) for(front in listOf(false,true)) {
            val matrix=cameraPreviewMatrix(640,480,300,400,rotation,front)
            val center=floatArrayOf(150f,200f);matrix.mapPoints(center)
            assertEquals(150f,center[0],0.01f);assertEquals(200f,center[1],0.01f)
            val inverse=android.graphics.Matrix();assertTrue(matrix.invert(inverse))
        }
        val normal=floatArrayOf(0f,200f);val mirrored=normal.copyOf()
        cameraPreviewMatrix(640,480,300,400,0,false).mapPoints(normal)
        cameraPreviewMatrix(640,480,300,400,0,true).mapPoints(mirrored)
        assertEquals(300f,normal[0]+mirrored[0],0.01f)
    }
    @Test fun platformYuvJpegRotationAndNoisyFramesAreVisibleAndBounded() {
        val y=ByteBuffer.wrap(byteArrayOf(40,40,200.toByte(),200.toByte()))
        val uv=ByteBuffer.wrap(byteArrayOf(128.toByte()))
        val bytes=cameraNv21(2,2,listOf(CameraPlane(y,2,1),CameraPlane(uv,1,1),CameraPlane(uv,1,1)))
        val out=ByteArrayOutputStream()
        assertTrue(YuvImage(bytes,ImageFormat.NV21,2,2,null).compressToJpeg(Rect(0,0,2,2),80,out))
        val jpeg=out.toByteArray();val decoded=BitmapFactory.decodeByteArray(jpeg,0,jpeg.size)
        assertNotNull(decoded);decoded!!.recycle()
        val source=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888)
        val pixels=IntArray(1280*720) { i -> 0xff000000.toInt() or ((i*1103515245+12345) and 0xffffff) }
        source.setPixels(pixels,0,1280,0,0,1280,720)
        val upright=transformVisualBitmap(source,90)
        try {
            assertTrue(source.isRecycled)
            assertEquals(360,upright.width);assertEquals(640,upright.height)
            val bounded=boundedVisualJpeg(upright)
            assertTrue(bounded.size<=96000)
            val preview=BitmapFactory.decodeByteArray(bounded,0,bounded.size)
            assertNotNull(preview);assertEquals(upright.width,preview!!.width);preview.recycle()
        } finally { upright.recycle() }
    }
}
