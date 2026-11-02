package dev.hermes.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What the build and app start promise: a shipped startup profile, resource shrinking that stays safe, and a main thread kept for the first frame. */
class StartupBuildTest {
    // Gradle runs unit tests from the app module; the other roots cover the Android project and the repository.
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "build.gradle.kts").isFile && File(it, "src/main/AndroidManifest.xml").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private val main = File(app, "src/main")
    private val sources = File(main, "java/dev/hermes/android")
    private val gradle = File(app, "build.gradle.kts").readText()

    private fun sourceFiles() = main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()

    @Test fun aStartupProfileShipsWithTheApp() {
        assertTrue("profileinstaller is a direct dependency, so the profile is installed on first run", gradle.contains("androidx.profileinstaller:profileinstaller:"))
        val rules = File(main, "baseline-prof.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val shape = Regex("""^[HSP]{1,3}L[A-Za-z0-9_/$*]+;->\*\*\(\*\*\)\*\*$""")
        assertTrue("every rule is a flagged method wildcard: ${rules.filterNot(shape::matches)}", rules.all(shape::matches))
        val named = rules.joinToString("\n")
        listOf("BeaconApplication", "BeaconActivity", "BeaconViewModel", "ui/ChatScreen", "ui/MoreScreen", "ui/BeaconSettings", "data/BeaconClient").forEach {
            assertTrue("$it is in the startup profile", named.contains("dev/hermes/android/$it"))
        }
    }

    @Test fun everyProfileRuleNamesCodeThatExists() {
        val rules = File(main, "baseline-prof.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        rules.forEach { rule ->
            val pattern = rule.substringAfter('L').substringBefore(';').removePrefix("dev/hermes/android").removePrefix("/")
            val directory = File(sources, pattern.substringBeforeLast('/', ""))
            val leaf = pattern.substringAfterLast('/')
            assertTrue("$rule: no such package", directory.isDirectory)
            if (leaf == "**") return@forEach
            val name = leaf.removePrefix("ComposableSingletons$").removeSuffix("*").removeSuffix("Kt")
            val files = directory.listFiles { file -> file.extension == "kt" || file.extension == "java" }.orEmpty()
            val exists = files.any { it.nameWithoutExtension.startsWith(name) } ||
                files.any { Regex("""\b(class|object|interface)\s+${Regex.escape(name)}""").containsMatchIn(it.readText()) }
            assertTrue("$rule: nothing in ${directory.path} is called $name", exists)
        }
    }

    @Test fun resourceShrinkingStaysSafeBecauseNothingIsLookedUpByName() {
        assertTrue("release and stable shrink resources", Regex("""release\s*\{[^}]*isShrinkResources\s*=\s*true""").containsMatchIn(gradle))
        // The shrinker keeps only resources it can see used; a lookup by name would hide one from it.
        val lookups = Regex("""\b(getIdentifier|getResourceName|getResourceEntryName|getResourcePackageName|getResourceTypeName)\b""")
        val offenders = sourceFiles().filter { lookups.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals("resources are looked up by name in $offenders; keep them in res/raw/keep.xml first", emptyList<String>(), offenders)
        assertTrue("the app has no raw or string resources to hide", File(main, "res").walkTopDown().none { it.isFile && (it.parentFile.name.startsWith("raw") || it.name == "strings.xml") })
    }

    @Test fun debugBuildsLogMainThreadIoAndNeverCrashOnIt() {
        val application = File(sources, "BeaconApplication.kt").readText()
        val policy = application.lineSequence().single { it.contains("StrictMode.setThreadPolicy") }
        assertTrue("only debug builds", policy.contains("BuildConfig.DEBUG"))
        listOf("detectDiskReads()", "detectDiskWrites()", "detectNetwork()", "penaltyLog()").forEach { assertTrue(it, policy.contains(it)) }
        assertFalse(policy.contains("penaltyDeath"))
        assertFalse(policy.contains("permitAll"))
    }
}
