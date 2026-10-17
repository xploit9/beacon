package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.uiText
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CypherBrandingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun launcherAndArabicAssistantUseCipherWithStablePackageAndPrivateFilesAuthority() {
        val app = ApplicationProvider.getApplicationContext<CypherApplication>()
        assertEquals("Cypher", app.packageManager.getApplicationLabel(app.applicationInfo).toString())
        assertTrue(app.packageName == "dev.hermes.android" || app.packageName == "dev.hermes.android.debug")
        assertNotNull(app.packageManager.resolveContentProvider("${app.packageName}.files", 0))
        compose.setContent { NativeLocalization("ar") { Text(uiText("Call Cypher")) } }
        compose.onNode(hasText("Cypher", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("Xara", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("Hermes", substring = true)).assertDoesNotExist()
        val catalogs = app.assets.open("native-locales.json").bufferedReader().use { JSONObject(it.readText()) }
        for (language in catalogs.keys()) {
            val values = catalogs.getJSONObject(language)
            for (key in values.keys()) {
                val translated = values.getString(key)
                assertFalse("Retired visible brand in $language/$key", Regex("""(?<![./_])\b(?:Hermes|Xara)\b""", RegexOption.IGNORE_CASE).containsMatchIn(translated))
            }
        }
    }
}
