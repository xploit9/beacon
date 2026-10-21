package dev.hermes.android

import android.app.Application
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import dev.hermes.android.data.AppCipher
import dev.hermes.android.data.AuthStore
import dev.hermes.android.data.ProjectionCache
import dev.hermes.android.voice.CallController
import kotlinx.coroutines.*

val android.content.Context.preferences by preferencesDataStore(name = "hermes-preferences")

class CypherApplication : Application() {
    val cipher by lazy { AppCipher() }
    val auth by lazy { AuthStore(this, cipher) }
    val cache by lazy { ProjectionCache(this, cipher) }
    val calls by lazy { CallController(this) }
    val push by lazy { dev.hermes.android.push.PushManager(this) }
    val updates by lazy { dev.hermes.android.updates.UpdateManager(this) }
    private val translations = lazy { runCatching { assets.open("native-locales.json").bufferedReader().use { org.json.JSONObject(it.readText()) } }.getOrDefault(org.json.JSONObject()) }
    val cachedTranslations get() = if (translations.isInitialized()) translations.value else null
    @androidx.annotation.WorkerThread fun readTranslations() = translations.value
    var firebaseConfigured = false; private set
    override fun onCreate() {
        super.onCreate()
        dev.hermes.android.data.NativePairing.attach(this)
        updates.schedule()
        val staleBefore = System.currentTimeMillis()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            launch { readTranslations() }
            launch { preferences.data.collect { dev.hermes.android.data.currentAssistantName = dev.hermes.android.data.AppPreferences.read(it).assistantName } }
            runCatching { push.reconcileIncomingDeadlines() }
            runCatching { cache.trim() }
            runCatching { java.io.File(cacheDir, "camera").listFiles()?.filter { it.isFile && it.lastModified() < staleBefore }?.forEach { it.delete() } }
            // Only temporary content created by this client, within its private cache directory.
            runCatching { cacheDir.listFiles()?.filter { it.isFile && it.lastModified() < staleBefore && it.name.startsWithAny("preview", "read-aloud", "upload", "pasted", "recorded", "hermes-export-") }?.forEach { it.delete() } }
                .onFailure { android.util.Log.w("CypherCache", "Temporary cache cleanup failed", it) }
        }
        if (BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_API_KEY.isNotBlank() && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() && BuildConfig.FIREBASE_SENDER_ID.isNotBlank()) {
            firebaseConfigured = runCatching { FirebaseApp.initializeApp(this, FirebaseOptions.Builder().setApplicationId(BuildConfig.FIREBASE_APP_ID).setApiKey(BuildConfig.FIREBASE_API_KEY).setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID).build()); true }
                .onFailure { android.util.Log.w("CypherPush", "Push initialization failed", it) }.getOrDefault(false)
        }
        dev.hermes.android.push.PushRegistrationJob.schedule(this)
    }
}

private fun String.startsWithAny(vararg prefixes: String) = prefixes.any(::startsWith)
