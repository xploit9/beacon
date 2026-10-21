package dev.hermes.android.voice
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class NativeCameraFrameTest {
    @Test fun paddedPlanarPlanesKeepRowOrderAndVuOrder() {
        val y=ByteBuffer.wrap(byteArrayOf(99,1,2,3,4,99,99,5,6,7,8));y.position(1)
        val u=ByteBuffer.wrap(byteArrayOf(20,21));val v=ByteBuffer.wrap(byteArrayOf(30,31))
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8,30,20,31,21),cameraNv21(4,2,listOf(CameraPlane(y,6,1),CameraPlane(u,2,1),CameraPlane(v,2,1))))
        assertEquals(1,y.position())
    }
    @Test fun interleavedChromaUsesPixelStrideAndCroppedOffsets() {
        val y=ByteBuffer.wrap(ByteArray(16) { it.toByte() })
        val u=ByteBuffer.wrap(byteArrayOf(20,99,21,99,22,99,23));val v=ByteBuffer.wrap(byteArrayOf(30,99,31,99,32,99,33))
        val planes=listOf(CameraPlane(y,4,1),CameraPlane(u,4,2),CameraPlane(v,4,2))
        assertArrayEquals(byteArrayOf(10,11,14,15,33,23),cameraNv21(2,2,planes,2,2))
        assertThrows(IllegalArgumentException::class.java) { cameraNv21(3,2,planes) }
        assertThrows(IllegalArgumentException::class.java) { cameraNv21(4,4,planes.map { it.copy(buffer=ByteBuffer.allocate(1)) }) }
    }
    @Test fun sensorAndEveryDisplayRotationAreExplicit() {
        assertEquals(listOf(90,180,270,0),(0..3).map { cameraFrameRotation(90,it*90,false) })
        assertEquals(listOf(270,180,90,0),(0..3).map { cameraFrameRotation(270,it*90,true) })
        assertThrows(IllegalArgumentException::class.java) { cameraFrameRotation(45,0,false) }
    }
}
