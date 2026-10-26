import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val owner = Properties().apply {
    val file = rootProject.file("owner.local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val signing = Properties().apply {
    val file = rootProject.file("../.signing/signing.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
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
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "dev.hermes.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.hermes.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 115
        versionName = "1.1.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_ORIGIN", quoted(owner.getProperty("native.origin", "")))
        buildConfigField("String", "FIREBASE_APP_ID", quoted(owner.getProperty("firebase.appId", "")))
        buildConfigField("String", "FIREBASE_API_KEY", quoted(owner.getProperty("firebase.apiKey", "")))
        buildConfigField("String", "FIREBASE_PROJECT_ID", quoted(owner.getProperty("firebase.projectId", "")))
        buildConfigField("String", "FIREBASE_SENDER_ID", quoted(owner.getProperty("firebase.senderId", "")))
    }
    if (signing.isNotEmpty()) {
        require(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !signing.getProperty(it).isNullOrBlank() }) { "signing.properties must define storeFile, storePassword, keyAlias and keyPassword" }
        signingConfigs.create("owner") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
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
        debug {
            applicationIdSuffix = ".debug"; versionNameSuffix = "-preview"
            // Local crash reports for test builds; blank (the default) keeps the reporter off.
            buildConfigField("String", "BUGSINK_DSN", quoted(System.getenv("BEACON_DEBUG_BUGSINK_DSN") ?: owner.getProperty("debug.bugsinkDsn", "")))
        }
        release {
            isMinifyEnabled = true
            if (signing.isNotEmpty()) signingConfig = signingConfigs.getByName("owner")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("stable") {
            initWith(getByName("release"))
            applicationIdSuffix = ".stable"
            isDebuggable = false
            matchingFallbacks += listOf("release")
            signingConfig = if (stableSigningConfigured) signingConfigs.getByName("stable") else null
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.window:window:1.4.0")
    implementation("androidx.core:core-telecom:1.0.0")
    implementation("androidx.room:room-runtime:2.8.2")
    implementation("androidx.room:room-ktx:2.8.2")
    ksp("androidx.room:room-compiler:2.8.2")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.browser:browser:1.10.0")
    implementation("org.commonmark:commonmark:0.25.1")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.25.1")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("ru.noties:jlatexmath-android:0.2.0")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation(platform("com.google.firebase:firebase-bom:34.4.0"))
    implementation("com.google.firebase:firebase-messaging")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
