package dev.hermes.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/** The APK and the Beacon plugin are always released together under one version number. */
class ReleaseVersionAlignmentTest {
    // Gradle runs unit tests from the app module; the other roots cover the Android project and the repository.
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "build.gradle.kts").isFile && File(it, "src/main/AndroidManifest.xml").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }

    private fun appVersion(gradle: String) = Regex("""\bversionName\s*=\s*"([^"]+)"""").find(gradle)?.groupValues?.get(1)
    private fun pluginVersion(manifest: String) = manifest.lineSequence().firstOrNull { it.startsWith("version:") }
        ?.substringAfter(':')?.substringBefore('#')?.trim()?.trim('\'', '"')

    @Test fun appAndPluginShareOneReleaseVersion() {
        val app = appVersion(File(app, "build.gradle.kts").readText())
        val plugin = pluginVersion(File(this.app, "../../hermes-plugin/beacon/plugin.yaml").readText())
        assertNotNull(app)
        assertEquals("APK and Beacon plugin release versions", app, plugin)
        assertEquals(app, BuildConfig.VERSION_NAME.removeSuffix("-preview"))
    }

    @Test fun aDifferentPluginVersionIsDetected() {
        assertNotEquals(appVersion("versionCode = 132\nversionName = \"1.1.22\""), pluginVersion("name: beacon\nversion: 1.1.19\n"))
        assertEquals("1.1.22", pluginVersion("version: '1.1.22' # release"))
    }
}
