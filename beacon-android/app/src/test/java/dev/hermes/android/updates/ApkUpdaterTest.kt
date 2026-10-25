package dev.hermes.android.updates

import dev.hermes.android.core.Release
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

    private val signer = "a".repeat(64)
    private val release = Release(113, "1.1.3", "dev.hermes.android.stable", "https://github.com/xploit9/beacon/releases/download/v1.1.3/Beacon-1.1.3.apk", "b".repeat(64), 1024, signer, "1.1.3")
    private fun refusal(archive: ArchiveFacts?, withRelease: Boolean = true, installed: Long = 112, installedSigner: String = signer) =
        archiveRefusal(archive, "dev.hermes.android.stable", installed, installedSigner, release.takeIf { withRelease })

    @Test fun onlyANewerBuildOfThisAppFromTheInstalledSignerMayReplaceIt() {
        val good = ArchiveFacts("dev.hermes.android.stable", 113, signer)
        assertNull(refusal(good))
        assertNull(refusal(good.copy(signer = signer.uppercase())))
        assertNull(refusal(good, withRelease = false))
    }

    @Test fun anythingElseIsRefusedBeforeItReachesAndroid() {
        val good = ArchiveFacts("dev.hermes.android.stable", 113, signer)
        assertNotNull(refusal(null))
        assertNotNull(refusal(good.copy(packageName = "evil.app")))
        assertNotNull(refusal(good.copy(version = 112))) // the same code is not an update
        assertNotNull(refusal(good.copy(version = 90))) // never a downgrade
        assertNotNull(refusal(good.copy(version = 114))) // the file is not the release it was announced as
        assertNotNull(refusal(good.copy(signer = null))) // several or missing signers
        assertNotNull(refusal(good.copy(signer = "c".repeat(64)))) // not the installed app's signer
        assertNotNull(refusal(good, installedSigner = "c".repeat(64)))
    }

    @Test fun theMetadataSignerMustMatchTheArchiveEvenWhenTheInstalledSignerDoes() {
        // A release whose metadata names another certificate than the APK carries is inconsistent, so it is refused.
        val good = ArchiveFacts("dev.hermes.android.stable", 113, signer)
        assertNull(archiveRefusal(good, "dev.hermes.android.stable", 112, signer, release))
        assertNotNull(archiveRefusal(good, "dev.hermes.android.stable", 112, signer, release.copy(signer = "c".repeat(64))))
        // The pre-install recheck has no release: it asks only for a newer build from the same signer.
        assertNull(archiveRefusal(good, "dev.hermes.android.stable", 112, signer))
        assertNotNull(archiveRefusal(good, "dev.hermes.android.stable", 113, signer))
    }
}
