package dev.hermes.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hermes.android.CypherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val licenseDocuments = listOf(
    "hermes-LICENSE.txt", "termux-LICENSE.txt", "conduit-LICENSE.txt", "androidsvg-LICENSE.txt",
    "androidsvg-NOTICE.txt", "jlatexmath-LICENSE.txt", "jlatexmath-COPYING.txt", "rich/MERMAID-LICENSE",
    "rich/JSOUP-LICENSE", "rich/CORE-JS-LICENSE", "hermes-mobile-app-LICENSE.txt", "hermes-relay-LICENSE.txt",
)

@Composable fun LicenseScreen(vm: CypherViewModel) {
    var selected by remember { mutableStateOf("hermes-LICENSE.txt") }
    val text by produceState("", selected) { value = withContext(Dispatchers.IO) { try { vm.app.assets.open(if (selected.startsWith("rich/")) selected else "licenses/$selected").bufferedReader().use { it.readText() } } catch (_: java.io.IOException) { "License text unavailable" } } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiText("Cypher and native rendering libraries"), style = MaterialTheme.typography.titleLarge)
        Text("Cypher Android · GPL-3.0-only. The complete corresponding source and retained upstream source archives accompany this private APK release.", style = MaterialTheme.typography.bodySmall)
        Text("Mermaid 11.17.2 · MIT. Source: https://github.com/mermaid-js/mermaid. Bundled offline diagram grammar and renderer.", style = MaterialTheme.typography.bodySmall)
        Text("jsoup 1.23.2 · MIT. Source: https://jsoup.org. Parses offline HTML before isolated previews.", style = MaterialTheme.typography.bodySmall)
        Text("core-js 3.50.0 · MIT. Source: https://github.com/zloirock/core-js. Selected compatibility functions for the isolated diagram renderer.", style = MaterialTheme.typography.bodySmall)
        Text("Reference design: omarqaterge/hermes-mobile-app · MIT. Source: https://github.com/omarqaterge/hermes-mobile-app, revision 2d5c5881764c218c4393567f8698db670fb9ad7e. Profile selector, navigation and rich content ideas are implemented in native Compose.", style = MaterialTheme.typography.bodySmall)
        Text("Hermes Relay sphere rendering · MIT. Adapted from Hermes Relay ui/components/MorphingSphere.kt and related sphere components.", style = MaterialTheme.typography.bodySmall)
        Text(uiText("JLaTeXMath Android 0.2.0"), style = MaterialTheme.typography.titleMedium)
        Text(uiText("Copyright 2017 Dimitry Ivanov and upstream contributors. GPL-2.0-or-later with the independent module linking exception. JLaTeXMath COPYING and font notices are retained with the corresponding source package."))
        Text(uiText("Source: https://github.com/noties/jlatexmath-android\nPinned commit: cc2a83dcf695028b5588f91a90734d94eb5bd02b"), style = MaterialTheme.typography.bodySmall)
        Text("AndroidSVG 1.4 · Apache-2.0. Copyright 2013 Paul LeBeau, Cave Rock Software Ltd. Source: https://github.com/BigBadaboom/androidsvg. Bounded static previews do not load external assets or entities.", style = MaterialTheme.typography.bodySmall)
        Choice("Document", selected, licenseDocuments, { selected = it })
        SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
    }
}
