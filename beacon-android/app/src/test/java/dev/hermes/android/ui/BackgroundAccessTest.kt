package dev.hermes.android.ui

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackgroundAccessTest {
    @Test fun eachStrictMakerIsRecognisedByManufacturerOrBrand() {
        mapOf(
            "HONOR" to "HONOR" to StrictMaker.HONOR, "HUAWEI" to "HUAWEI" to StrictMaker.HUAWEI,
            "Xiaomi" to "Xiaomi" to StrictMaker.XIAOMI, "Xiaomi" to "Redmi" to StrictMaker.XIAOMI, "Xiaomi" to "POCO" to StrictMaker.XIAOMI,
            "OPPO" to "OPPO" to StrictMaker.OPPO, "OnePlus" to "OnePlus" to StrictMaker.OPPO, "realme" to "realme" to StrictMaker.OPPO,
            "vivo" to "vivo" to StrictMaker.VIVO, "vivo" to "iQOO" to StrictMaker.VIVO, "samsung" to "samsung" to StrictMaker.SAMSUNG,
        ).forEach { (names, expected) -> assertEquals(names.toString(), expected, strictMaker(names.first, names.second)) }
    }

    @Test fun anHonorPhoneFromTheHuaweiYearsStillCountsAsHonor() {
        assertEquals(StrictMaker.HONOR, strictMaker("HUAWEI", "honor"))
    }

    @Test fun otherPhonesAndBlankNamesAreNotStrict() {
        assertNull(strictMaker("Google", "google"))
        assertNull(strictMaker("motorola", "motorola"))
        assertNull(strictMaker("", ""))
    }

    @Test fun everyMakerHasScreensAndEachIsNamedOnce() {
        StrictMaker.entries.forEach { maker ->
            val screens = backgroundScreens(maker)
            assertTrue(maker.name, screens.isNotEmpty())
            assertEquals(maker.name, screens.size, screens.toSet().size)
            screens.forEach { (pkg, cls) -> assertTrue("$maker $pkg/$cls", pkg.contains('.') && cls.contains('.') && !cls.endsWith(".")) }
        }
    }

    @Test fun honorTriesItsOwnManagerFirstThenHuaweisAndHuaweiNeverTriesHonors() {
        val honor = backgroundScreens(StrictMaker.HONOR)
        assertEquals("com.hihonor.systemmanager", honor.first().first)
        assertTrue(honor.containsAll(backgroundScreens(StrictMaker.HUAWEI)))
        assertTrue(backgroundScreens(StrictMaker.HUAWEI).none { it.first.startsWith("com.hihonor") })
    }

    @Test fun theOverlayHintIsForAnyInstallOutsideAStoreWhileOverlayIsOff() {
        // A file install, and Beacon's own updates (source unspecified), can be locked; only a store install is not.
        listOf(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE, PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE, PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED,
            PackageInstaller.PACKAGE_SOURCE_OTHER, null).forEach { source ->
            assertTrue("source $source", overlayNeedsRestrictedAllow(35, source, overlayGranted = false))
            assertTrue(overlayNeedsRestrictedAllow(33, source, overlayGranted = false))
            assertFalse("granted", overlayNeedsRestrictedAllow(35, source, overlayGranted = true))
            assertFalse("before Android 13", overlayNeedsRestrictedAllow(32, source, overlayGranted = false))
        }
        assertFalse("store", overlayNeedsRestrictedAllow(36, PackageInstaller.PACKAGE_SOURCE_STORE, overlayGranted = false))
    }

    @Test fun theManifestQueriesNameEveryPackageABackgroundScreenStartsIn() {
        // Android 11+ hides a package the manifest does not list, so a missing entry makes that maker's screen fall through to App info.
        val manifest = listOf("src/main", "app/src/main", "beacon-android/app/src/main").map { File(it, "AndroidManifest.xml") }.firstOrNull { it.isFile }
        assertNotNull("AndroidManifest.xml not found from ${File("").absolutePath}", manifest)
        val text = manifest!!.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val queries = Regex("<queries>(.*?)</queries>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)
        assertNotNull("The manifest has no <queries>", queries)
        val listed = Regex("""<package\s+android:name="([^"]+)"""").findAll(queries!!).map { it.groupValues[1] }.toSet()
        val missing = StrictMaker.entries.flatMap { maker -> backgroundScreens(maker).map { it.first } }.toSet() - listed
        assertTrue("Packages in backgroundScreens but not in the manifest <queries>: $missing", missing.isEmpty())
    }
}
