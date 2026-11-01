package dev.hermes.android.uiux.gaps

import org.json.JSONObject
import java.io.File

/** The bundled assets the gap tests read straight from the source tree (Gradle runs unit tests from the app module). */
internal object GapsAssets {
    val appDir: File by lazy {
        requireNotNull(listOf(".", "app", "beacon-android/app").map(::File).firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) { "app module not found" }
    }
    val locales: JSONObject by lazy { JSONObject(File(appDir, "src/main/assets/native-locales.json").readText()) }
    val languages: List<String> by lazy { locales.keys().asSequence().toList() }

    /** The catalog's lookup key for an English text. */
    fun key(text: String) = text.lowercase().replace("…", "...").replace(Regex("\\s+"), " ").trim()

    /** [text] in [language], blank when the catalog has no entry. */
    fun translated(language: String, text: String): String = locales.getJSONObject(language).optString(key(text))

    fun asset(path: String): File = File(appDir, "src/main/assets/$path")
}
