package dev.hermes.android

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicitly opted-in system-installer journey on the owned emulator, with the real signed package. */
class ProductionUpdateJourneyTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext
    private val production = "dev.hermes.android"
    private val marker = "Local update verification draft; do not send."
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) { if (node == null) return; result += node; for (i in 0 until node.childCount) visit(node.getChild(i)) }
        visit(automation.rootInActiveWindow); return result
    }
    private fun find(label: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString().equals(label, true) || it.contentDescription?.toString().equals(label, true)) }
    private fun await(timeout: Long = 30_000, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!ready() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        check(ready()) { "Production UI did not reach the expected state" }
    }
    private fun click(label: String) {
        await { find(label) != null }
        val node = checkNotNull(find(label)); var action: AccessibilityNodeInfo? = node
        while (action != null && !action.isClickable) action = action.parent
        check(action != null && action.isEnabled && action.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "UI action was not available: $label" }
    }
    private fun fields() = nodes().filter { it.isVisibleToUser && it.className == "android.widget.EditText" }
    private fun text(node: AccessibilityNodeInfo, value: String) = check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
        Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })) { "Text field rejected input" }
    private fun launch() { context.startActivity(Intent().setClassName(production, "dev.hermes.android.CypherActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
    private fun version(): Int = checkNotNull(Regex("versionCode=(\\d+)").find(shell("dumpsys package $production"))) { "The production package has no version code" }.groupValues[1].toInt()
    private fun capture(name: String) {
        val image = checkNotNull(automation.takeScreenshot()); val directory = File(context.filesDir, "production-ui").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    @Test(timeout = 240_000) fun signedUpdateUsesSystemInstallerAndKeepsLoginAndDraft() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cypherProductionUi") == "1")
        check(context.packageName.endsWith(".debug") && Build.VERSION.SDK_INT == 36 && (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")))
        val phase = args.getString("productionPhase")
        require(phase in setOf("prepare", "install"))
        launch()
        if (phase == "prepare") {
            assertEquals(BuildConfig.VERSION_CODE-1, version())
            await { find("Sign in") != null || find("Show navigation") != null }
            if (find("Sign in") != null) {
                val credential = JSONObject(File(context.filesDir, "live-auth.json").readText(Charsets.UTF_8).removePrefix("\uFEFF"))
                check(fields().size == 3 && fields()[2].isPassword)
                text(fields()[0], BuildConfig.DEFAULT_ORIGIN)
                text(fields()[1], credential.getString("username"))
                text(fields()[2], credential.getString("password"))
                click("Sign in")
            }
            await(60_000) { find("Show navigation") != null }
            await { fields().size == 1 }
            val field = fields().single()
            check(field.text.isNullOrBlank() || field.text.toString() == "Message ${dev.hermes.android.data.currentAssistantName}") { "Never replace an existing owner's draft as QA" }
            text(field, marker)
            await { fields().any { it.text?.toString() == marker } }
            click("Show navigation"); click("Settings"); click("App updates")
            await { find("Update the app automatically")?.isChecked == true && find("Update the Beacon plugin automatically") != null }
            click("Update the app automatically") // Keep this journey focused on the manual update row.
            shell("input keyevent 3"); SystemClock.sleep(1_000)
            shell("am force-stop $production"); launch()
            await(60_000) { fields().any { it.text?.toString() == marker } }
            assertNull(find("Sign in")); capture("before-update")
            File(context.filesDir, "production-update-before.json").writeText(json("version_code" to version(), "authenticated" to true, "draft_retained" to true).toString())
            return
        }
        check(File(context.filesDir, "production-update-before.json").exists())
        assertEquals(BuildConfig.VERSION_CODE-1, version())
        await(60_000) { find("Show navigation") != null }
        assertNull(find("Sign in"))
        val originalInstallMode=Regex("REQUEST_INSTALL_PACKAGES: (allow|deny|ignore|default)").find(shell("appops get $production REQUEST_INSTALL_PACKAGES"))?.groupValues?.get(1) ?: "default"
        try {
        click("Show navigation"); click("Settings"); click("App updates")
        await { find("Update the app automatically") != null }
        assertFalse(find("Update the app automatically")?.isChecked ?: true)
        assertNotNull(find("Update the Beacon plugin automatically"))
        if(find("Check for updates") != null) click("Check for updates")
        capture("update-entry")
        // This is the app's actual authenticated downloader and verified installer action.
        val updateLabels = setOf("Update", "Download update", "Install update", "Update now")
        await { updateLabels.any { find(it) != null } }
        click(updateLabels.first { find(it) != null })
        var allowedSource = false
        val deadline = SystemClock.elapsedRealtime() + 100_000
        while (version() < BuildConfig.VERSION_CODE && SystemClock.elapsedRealtime() < deadline) {
            val pkg = automation.rootInActiveWindow?.packageName?.toString().orEmpty()
            when {
                pkg == "com.android.settings" && find("Allow from this source") != null -> {
                    val toggle = nodes().firstOrNull { it.isCheckable && it.isVisibleToUser }
                    if (toggle?.isChecked != true) click("Allow from this source")
                    allowedSource = true; shell("input keyevent 4"); launch() // Android may kill the caller when this permission changes.
                }
                pkg in setOf("com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.permissioncontroller") && find("Update") != null -> { capture("installer-confirmation"); click("Update") }
                pkg in setOf("com.android.packageinstaller", "com.google.android.packageinstaller") && find("Install") != null -> { capture("installer-confirmation"); click("Install") }
                pkg == production && find("Install update") != null -> click("Install update")
                else -> SystemClock.sleep(200)
            }
        }
        assertEquals(BuildConfig.VERSION_CODE, version())
        launch(); await(60_000) { find("Show navigation") != null }
        assertNull(find("Sign in"))
        // Back restores the same conversation; selecting another chat would not prove retention.
        repeat(2) { if(fields().none { it.text?.toString()==marker }) { shell("input keyevent 4");SystemClock.sleep(500) } }
        await { fields().any { it.text?.toString()==marker } }
        capture("after-update")
        text(fields().single { it.text?.toString()==marker }, "")
        shell("input keyevent 3");SystemClock.sleep(500)
        File(context.filesDir, "production-update-after.json").writeText(json("version_code" to version(), "authenticated" to true,
            "draft_retained" to true, "system_installer" to true, "unknown_source_return" to allowedSource).toString())
        } finally { shell("appops set $production REQUEST_INSTALL_PACKAGES $originalInstallMode") }
    }
}
