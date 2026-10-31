package dev.hermes.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hermes.android.BeaconViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val licenseDocuments = listOf(
    "hermes-LICENSE.txt", "conduit-LICENSE.txt", "androidsvg-LICENSE.txt",
    "androidsvg-NOTICE.txt", "jlatexmath-LICENSE.txt", "jlatexmath-COPYING.txt", "rich/MERMAID-LICENSE",
    "rich/JSOUP-LICENSE", "rich/KATEX-LICENSE", "rich/CORE-JS-LICENSE", "hermes-mobile-app-LICENSE.txt", "hermes-relay-LICENSE.txt",
)

@Composable fun LicenseScreen(vm: BeaconViewModel) {
    var selected by remember { mutableStateOf("hermes-LICENSE.txt") }
    val unavailable = uiText("License text unavailable")
    val text by produceState("", selected) { value = withContext(Dispatchers.IO) { try { vm.app.assets.open(if (selected.startsWith("rich/")) selected else "licenses/$selected").bufferedReader().use { it.readText() } } catch (_: java.io.IOException) { unavailable } } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiText("Beacon and native rendering libraries"), style = MaterialTheme.typography.titleLarge)
        Text("Beacon Android · GPL-3.0-only. Complete corresponding source: https://github.com/xploit9/beacon.", style = MaterialTheme.typography.bodySmall) // verbatim
        Text("Mermaid 11.17.2 · MIT. Source: https://github.com/mermaid-js/mermaid. Bundled offline diagram grammar and renderer.", style = MaterialTheme.typography.bodySmall) // verbatim
        Text("KaTeX 0.18.2 · MIT. Source: https://github.com/KaTeX/KaTeX. Bundled offline math in Mermaid diagrams.", style = MaterialTheme.typography.bodySmall) // verbatim
        Text("jsoup 1.23.2 · MIT. Source: https://jsoup.org. Parses offline HTML before isolated previews.", style = MaterialTheme.typography.bodySmall) // verbatim
        Text("core-js 3.50.0 · MIT. Source: https://github.com/zloirock/core-js. Selected compatibility functions for the isolated diagram renderer.", style = MaterialTheme.typography.bodySmall) // verbatim
        Text("Reference design: omarqaterge/hermes-mobile-app · MIT. Source: https://github.com/omarqaterge/hermes-mobile-app, revision 2d5c5881764c218c4393567f8698db670fb9ad7e. Profile selector, navigation and rich content ideas are implemented in native Compose.", style = MaterialTheme.typography.bodySmall) // verbatim
        Text("Hermes Relay · MIT. Adapted code includes chat presentation, NativeAuth, NativeOperations, OpenAiWire, RelayCallback, RelayCallbackPolicy and sphere rendering. SettingsSearch follows Relay’s search pattern. See the source headers and retained Hermes Relay MIT notice.", style = MaterialTheme.typography.bodySmall) // verbatim
        Text(uiText("JLaTeXMath Android 0.2.0"), style = MaterialTheme.typography.titleMedium)
        Text(uiText("Copyright 2017 Dimitry Ivanov and upstream contributors. GPL-2.0-or-later with the independent module linking exception. Full license texts are in the document list below."))
        Text(uiText("Source: https://github.com/noties/jlatexmath-android\nPinned commit: cc2a83dcf695028b5588f91a90734d94eb5bd02b"), style = MaterialTheme.typography.bodySmall)
        Text("AndroidSVG 1.4 · Apache-2.0. Copyright 2013 Paul LeBeau, Cave Rock Software Ltd. Source: https://github.com/BigBadaboom/androidsvg. Bounded static previews do not load external assets or entities.", style = MaterialTheme.typography.bodySmall) // verbatim
        Choice("Document", selected, licenseDocuments, { selected = it })
        SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
    }
}
