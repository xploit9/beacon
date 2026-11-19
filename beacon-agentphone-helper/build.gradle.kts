import java.security.MessageDigest
import java.util.Properties

// Kotlin 2.4 metadata needs R8 9.1.29+; AGP 8.13 bundles an older R8 (developer.android.com/build/kotlin-support).
buildscript {
    repositories { google() }
    dependencies { classpath("com.android.tools:r8:9.1.57") }
}

plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.4.21"
}

// The release is signed with Beacon's stable release key, read the same way as the app's stable build
// (beaconStableSigning / BEACON_STABLE_SIGNING: a properties file outside the repository). Without it the release
// is unsigned, unless a local test run asks for the debug key with -PhelperLocalDebugSigning=true.
val stableSigningFile = providers.gradleProperty("beaconStableSigning")
    .orElse(providers.environmentVariable("BEACON_STABLE_SIGNING"))
    .orNull?.takeIf { it.isNotBlank() }?.let { File(it).canonicalFile }
val stableSigning = Properties().apply {
    stableSigningFile?.let { file ->
        require(!file.toPath().startsWith(rootProject.projectDir.parentFile.canonicalFile.toPath())) {
            "beaconStableSigning must point to a properties file outside the repository"
        }
        if (file.isFile) file.inputStream().use { load(it) }
    }
}
val stableSigningConfigured = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !stableSigning.getProperty(it).isNullOrBlank() }
val localDebugSigning = providers.gradleProperty("helperLocalDebugSigning").orNull == "true"

android {
    // The package and component names are part of the channel contract (docs/agent-phone/channel-v1.md) and of the
    // plugin's first-start settings, so no build type adds a suffix.
    namespace = "dev.hermes.agentphone.helper"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.hermes.agentphone.helper"
        minSdk = 30
        // 33 is what spikes S8-S14 measured on the agent phone (Android 16). The helper is installed only by the plugin
        // over adb, never from a store, so the store's target level does not apply.
        targetSdk = 33
        versionCode = 121
        versionName = "1.2.1"
    }
    if (stableSigningConfigured) {
        signingConfigs.create("stable") {
            val configuredStore = File(stableSigning.getProperty("storeFile"))
            val stableStoreFile = if (configuredStore.isAbsolute) configuredStore else File(stableSigningFile!!.parentFile, configuredStore.path)
            require(!stableStoreFile.canonicalFile.toPath().startsWith(rootProject.projectDir.parentFile.canonicalFile.toPath())) {
                "The stable signing keystore must be outside the repository"
            }
            storeFile = stableStoreFile
            storePassword = stableSigning.getProperty("storePassword")
            keyAlias = stableSigning.getProperty("keyAlias")
            keyPassword = stableSigning.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = when {
                stableSigningConfigured -> signingConfigs.getByName("stable")
                localDebugSigning -> signingConfigs.getByName("debug")
                else -> null
            }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint {
        // Sideloaded over adb only (see targetSdk), and it must read the signing certificates of whichever app it fills.
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi", "QueryAllPackagesPermission")
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// The pin the plugin checks before it installs the helper (EMU-DESIGN §6.6; Root, 17 Nov): the signed release APK's SHA-256
// and its one signing certificate's SHA-256, with its size and version. At release time Root runs this with the stable key,
// publishes the APK beside the app's in the same GitHub release and writes both digests into the plugin. Never commit an APK.
val releaseSigned = stableSigningConfigured || localDebugSigning
val pinRelease by tasks.registering {
    description = "Writes build/outputs/apk/release/helper-pin.json for the signed release APK."
    dependsOn("assembleRelease")
    val apkDir = layout.buildDirectory.dir("outputs/apk/release")
    val versionCode = android.defaultConfig.versionCode
    val versionName = android.defaultConfig.versionName
    val signedBy = if (stableSigningConfigured) "stable" else "local-debug"
    val apksigner = File(android.sdkDirectory, "build-tools/${android.buildToolsVersion}/" +
        if (System.getProperty("os.name").startsWith("Windows")) "apksigner.bat" else "apksigner")
    doLast {
        check(releaseSigned) { "The release is unsigned: configure beaconStableSigning before pinning it" }
        val apk = apkDir.get().asFile.listFiles { f -> f.name.endsWith(".apk") }!!.single()
        val digest = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { b -> "%02x".format(b) }
        val verify = ProcessBuilder(apksigner.path, "verify", "--print-certs", apk.path).redirectErrorStream(true).start()
        val out = verify.inputStream.bufferedReader().readText()
        check(verify.waitFor() == 0) { "apksigner could not verify the release APK" }
        val certs = Regex("""Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})""").findAll(out).map { it.groupValues[1] }.toList()
        check(certs.size == 1) { "The release APK must have exactly one signer" }
        File(apk.parentFile, "helper-pin.json").writeText(
            "{\"package\":\"dev.hermes.agentphone.helper\",\"version_code\":$versionCode,\"version_name\":\"$versionName\"," +
                "\"file\":\"${apk.name}\",\"bytes\":${apk.length()},\"sha256\":\"$digest\",\"signer_sha256\":\"${certs[0]}\"," +
                "\"signed_by\":\"$signedBy\"}\n",
        )
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
