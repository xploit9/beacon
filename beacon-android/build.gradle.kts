// Kotlin 2.4 metadata needs R8 9.1.29+; AGP 8.13 bundles an older R8 (developer.android.com/build/kotlin-support).
buildscript {
    repositories { google() }
    dependencies { classpath("com.android.tools:r8:9.1.57") }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
