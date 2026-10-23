package dev.hermes.android.updates

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ApkUpdaterTest {
    @Test fun stalePartialDownloadsAreRemovedButConcurrentDownloadsAndVerifiedApksRemain() {
        val dir=Files.createTempDirectory("beacon-update-test").toFile()
        val now=System.currentTimeMillis()
        fun file(name:String,age:Long)=dir.resolve(name).apply { writeText("fixture");check(setLastModified(now-age)) }
        try {
            val stale=file("killed.part",3_600_001)
            val active=file("active.part",30_000)
            val boundary=file("boundary.part",3_600_000)
            val future=file("future.part",-1000)
            val verified=file("verified.apk",7_200_000)
            val directory=dir.resolve("folder.part").apply { mkdir() }
            prunePartialUpdates(dir,now)
            assertFalse(stale.exists())
            assertTrue(active.exists());assertTrue(boundary.exists());assertTrue(future.exists())
            assertTrue(verified.exists());assertTrue(directory.isDirectory)
            prunePartialUpdates(dir,now)
            assertTrue(active.exists())
        } finally { dir.deleteRecursively() }
    }
}
