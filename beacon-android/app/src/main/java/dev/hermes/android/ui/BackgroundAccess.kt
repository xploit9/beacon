package dev.hermes.android.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Phone makers whose own power manager stops background apps even after the battery exemption. */
internal enum class StrictMaker { HUAWEI, HONOR, XIAOMI, OPPO, VIVO, SAMSUNG }

/** [manufacturer] and [brand] are `Build.MANUFACTURER` and `Build.BRAND`; the brand tells Redmi, POCO, realme and iQOO apart from their parent. */
internal fun strictMaker(manufacturer: String, brand: String = ""): StrictMaker? {
    val names = listOf(manufacturer, brand).map { it.trim().lowercase() }
    fun named(vararg wanted: String) = names.any { it in wanted }
    return when {
        // Honor phones from the Huawei years still report HUAWEI as manufacturer, so the brand comes first.
        named("honor") -> StrictMaker.HONOR
        named("huawei") -> StrictMaker.HUAWEI
        named("xiaomi", "redmi", "poco") -> StrictMaker.XIAOMI
        named("oppo", "oneplus", "realme") -> StrictMaker.OPPO
        named("vivo", "iqoo") -> StrictMaker.VIVO
        named("samsung") -> StrictMaker.SAMSUNG
        else -> null
    }
}

internal fun thisPhoneStrictMaker(): StrictMaker? = strictMaker(Build.MANUFACTURER, Build.BRAND)

// shortcut: component names come from public maker lists (AutoStarter, dontkillmyapp.com), not a device probe; a wrong or removed one is skipped, so re-check on a new maker or OS.
private val huaweiScreens = listOf(
    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
    "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
    "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
)

/** The package and activity of each screen where the maker lets an app start itself and run in the background, best first. */
internal fun backgroundScreens(maker: StrictMaker): List<Pair<String, String>> = when (maker) {
    StrictMaker.HUAWEI -> huaweiScreens
    // MagicOS moved the manager to its own package; older Honor phones still use Huawei's.
    StrictMaker.HONOR -> listOf("com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity") + huaweiScreens
    StrictMaker.XIAOMI -> listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity")
    StrictMaker.OPPO -> listOf(
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
    )
    StrictMaker.VIVO -> listOf(
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
    )
    StrictMaker.SAMSUNG -> listOf(
        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
    )
}

/**
 * Opens this phone maker's background screen, else Beacon's App info. The makers' packages are listed in the manifest's <queries>;
 * each screen is tried by starting it, and a phone without it (or a screen that is not exported) throws and the next one is tried.
 */
internal fun openMakerBackgroundSettings(context: Context): Boolean {
    thisPhoneStrictMaker()?.let { maker ->
        backgroundScreens(maker).forEach { (pkg, cls) ->
            if (runCatching { context.startActivity(Intent().setClassName(pkg, cls)) }.isSuccess) return true
        }
    }
    return openAppInfo(context)
}

internal fun openAppInfo(context: Context): Boolean =
    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }.isSuccess

/**
 * Android locks "Display over other apps" for an app installed outside a store until App info › Allow restricted settings.
 * Beacon's own updates leave the source unspecified, so anything but a store install that came back without the overlay gets the hint.
 */
internal fun overlayNeedsRestrictedAllow(sdk: Int, packageSource: Int?, overlayGranted: Boolean): Boolean =
    !overlayGranted && sdk >= 33 && packageSource != PackageInstaller.PACKAGE_SOURCE_STORE

/** How Android says this app was installed; null before Android 13 or when it can't be read. */
internal fun installPackageSource(context: Context): Int? =
    if (Build.VERSION.SDK_INT >= 33) runCatching { context.packageManager.getInstallSourceInfo(context.packageName).packageSource }.getOrNull() else null
