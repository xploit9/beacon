package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.NativeSkinTheme
import dev.hermes.android.ui.LocalAssistantName
import dev.hermes.android.ui.assistantText
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CypherBrandingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun assistantCopyStartsFromBeaconAndUsesTheChosenName() {
        compose.setContent {
            CompositionLocalProvider(LocalAssistantName provides "Nova") {
                Text(assistantText("Beacon chat"))
            }
        }
        compose.onNodeWithText("Nova chat").assertExists()
    }

    @Test fun darkSkinGivesPlainTextTheBackgroundForegroundColor() {
        var observed = Color.Unspecified
        var expected = Color.Unspecified
        compose.setContent {
            NativeSkinTheme(dark = true, skin = "default") {
                observed = LocalContentColor.current
                expected = MaterialTheme.colorScheme.onBackground
                Text("Plain text")
            }
        }
        compose.waitForIdle()
        assertEquals(expected, observed)
    }

    @Test fun launcherKeepsCypherAndArabicAssistantUsesChosenNameWithStablePackageAndPrivateFilesAuthority() {
        val app = ApplicationProvider.getApplicationContext<CypherApplication>()
        assertTrue(app.packageName == "dev.hermes.android" || app.packageName == "dev.hermes.android.debug")
        assertEquals(if (app.packageName.endsWith(".debug")) "Cypher Preview" else "Cypher", app.packageManager.getApplicationLabel(app.applicationInfo).toString())
        assertNotNull(app.packageManager.resolveContentProvider("${app.packageName}.files", 0))
        compose.setContent { NativeLocalization("ar") { CompositionLocalProvider(LocalAssistantName provides "Nova") { Text(assistantText("Call Beacon")) } } }
        compose.onNode(hasText("Call Nova", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("Hermes", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("Xara", substring = true)).assertDoesNotExist()
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
