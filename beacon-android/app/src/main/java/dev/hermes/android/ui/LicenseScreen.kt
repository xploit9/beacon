@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.hermes.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.BuildConfig
import dev.hermes.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val licenseDocuments = listOf(
    "hermes-LICENSE.txt", "conduit-LICENSE.txt", "androidsvg-LICENSE.txt",
    "androidsvg-NOTICE.txt", "jlatexmath-LICENSE.txt", "jlatexmath-COPYING.txt", "rich/MERMAID-LICENSE",
    "rich/JSOUP-LICENSE", "rich/KATEX-LICENSE", "rich/CORE-JS-LICENSE", "hermes-mobile-app-LICENSE.txt", "hermes-relay-LICENSE.txt",
)

/** A bundled work: its name, license id, and the notice exactly as the source states it. Notices are legal text, so they stay untranslated. */
internal data class LicensedWork(val name: String, val license: String, val notice: String)

/** Every bundled work, in the order the notices have always been shown. */
internal val openSourceWorks = listOf(
    LicensedWork("Beacon", "GPL-3.0-only", "Beacon Android · GPL-3.0-only. Complete corresponding source: https://github.com/xploit9/beacon."), // verbatim
    LicensedWork("Mermaid 11.17.2", "MIT", "Mermaid 11.17.2 · MIT. Source: https://github.com/mermaid-js/mermaid. Bundled offline diagram grammar and renderer."), // verbatim
    LicensedWork("KaTeX 0.18.2", "MIT", "KaTeX 0.18.2 · MIT. Source: https://github.com/KaTeX/KaTeX. Bundled offline math in Mermaid diagrams."), // verbatim
    LicensedWork("jsoup 1.23.2", "MIT", "jsoup 1.23.2 · MIT. Source: https://jsoup.org. Parses offline HTML before isolated previews."), // verbatim
    LicensedWork("core-js 3.50.0", "MIT", "core-js 3.50.0 · MIT. Source: https://github.com/zloirock/core-js. Selected compatibility functions for the isolated diagram renderer."), // verbatim
    LicensedWork("hermes-mobile-app reference", "MIT", "Reference design: omarqaterge/hermes-mobile-app · MIT. Source: https://github.com/omarqaterge/hermes-mobile-app, revision 2d5c5881764c218c4393567f8698db670fb9ad7e. Profile selector, navigation and rich content ideas are implemented in native Compose."), // verbatim
    LicensedWork("Hermes Relay", "MIT", "Hermes Relay · MIT. Adapted code includes chat presentation, NativeAuth, NativeOperations, OpenAiWire, RelayCallback, RelayCallbackPolicy and sphere rendering. SettingsSearch follows Relay’s search pattern. See the source headers and retained Hermes Relay MIT notice."), // verbatim
    LicensedWork("JLaTeXMath Android 0.2.0", "GPL-2.0-or-later", "Copyright 2017 Dimitry Ivanov and upstream contributors. GPL-2.0-or-later with the independent module linking exception. Full license texts are in the document list below.\nSource: https://github.com/noties/jlatexmath-android\nPinned commit: cc2a83dcf695028b5588f91a90734d94eb5bd02b"), // verbatim
    LicensedWork("AndroidSVG 1.4", "Apache-2.0", "AndroidSVG 1.4 · Apache-2.0. Copyright 2013 Paul LeBeau, Cave Rock Software Ltd. Source: https://github.com/BigBadaboom/androidsvg. Bounded static previews do not load external assets or entities."), // verbatim
)

@Composable fun LicenseScreen(vm: BeaconViewModel) {
    val uriHandler = LocalUriHandler.current
    var openWork by remember { mutableStateOf<LicensedWork?>(null) }
    var openDocument by remember { mutableStateOf<String?>(null) }
    SettingsList(testTag = "licenses-page") {
        item {
            SettingsStatusHero(null, "Beacon ${BuildConfig.VERSION_NAME}", line = "GPL-3.0-only", painter = painterResource(R.drawable.ic_beacon_logo),
                secondary = SettingsAction(uiText("Source code"), { uriHandler.openUri("https://github.com/xploit9/beacon") }, testTag = "license-source"),
                testTag = "license-hero")
        }
        item {
            SettingsSection(title = uiText("Open source"), testTag = "license-open-source") {
                openSourceWorks.forEachIndexed { index, work ->
                    if (index > 0) SettingsDivider()
                    SettingsNavRow(work.name, { openWork = work }, icon = Icons.Rounded.Code, value = work.license, testTag = "license-work-${work.name}")
                }
            }
        }
        item {
            SettingsSection(title = uiText("License texts"), testTag = "license-texts") {
                licenseDocuments.forEachIndexed { index, document ->
                    if (index > 0) SettingsDivider()
                    SettingsNavRow(document, { openDocument = document }, icon = Icons.Rounded.Description, testTag = "license-document-$document")
                }
            }
        }
    }
    openWork?.let { work ->
        ModalBottomSheet(onDismissRequest = { openWork = null }) { LicenseNoticeSheet(work) }
    }
    openDocument?.let { document ->
        ModalBottomSheet(onDismissRequest = { openDocument = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            LicenseTextSheet(vm, document)
        }
    }
}

@Composable private fun LicenseNoticeSheet(work: LicensedWork) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(work.name, style = MaterialTheme.typography.titleLarge)
        SelectionContainer { Text(work.notice, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable private fun LicenseTextSheet(vm: BeaconViewModel, document: String) {
    val unavailable = uiText("License text unavailable")
    val text by produceState("", document) { value = withContext(Dispatchers.IO) { try { vm.app.assets.open(if (document.startsWith("rich/")) document else "licenses/$document").bufferedReader().use { it.readText() } } catch (_: java.io.IOException) { unavailable } } }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(document, style = MaterialTheme.typography.titleMedium)
        SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
    }
}
