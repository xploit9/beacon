package dev.hermes.android.ui

import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val RICH_LIMIT = 64_000
internal fun safeRichHtml(source: String): String {
    require(source.length <= RICH_LIMIT) { "HTML exceeds the 64,000 character preview limit" }
    val allowed = Safelist.none().addTags("p", "div", "span", "h1", "h2", "h3", "h4", "strong", "b", "em", "i", "u", "s", "br", "hr", "ul", "ol", "li", "blockquote", "pre", "code", "table", "thead", "tbody", "tr", "th", "td", "details", "summary")
    val doc = Jsoup.parseBodyFragment(source)
    doc.select("script,style,iframe,object,embed,svg,math,form,input,button,link,meta,base").remove()
    return Jsoup.clean(doc.body().html(), allowed)
}
internal fun richPage(source: String, diagram: Boolean, dark: Boolean, foreground: String = if(dark) "#F4EEE5" else "#20272D", background: String = if(dark) "#151C22" else "#ECE7DE", fontScale: Float = 1f, rendering: String = "Rendering diagram...", failed: String = "Diagram could not be rendered. Open Source to read the original."): String {
    require(source.length <= RICH_LIMIT) { "Preview exceeds the 64,000 character limit" }
    val body = if (diagram) "<pre class=mermaid>" + org.jsoup.nodes.Entities.escape(source) + "</pre><p id=status>" + org.jsoup.nodes.Entities.escape(rendering) + "</p><p id=failed hidden>" + org.jsoup.nodes.Entities.escape(failed) + "</p>" else safeRichHtml(source)
    val scripts = if (diagram) """<script src="https://rich.invalid/polyfills.js"></script><script src="https://rich.invalid/mermaid.js"></script><script>
    const status=document.getElementById('status');let settled=false;
    const fail=()=>{if(settled)return;settled=true;document.querySelector('.mermaid').textContent=document.getElementById('failed').textContent;status.remove()};
    const timeout=setTimeout(fail,10000);
    try{mermaid.initialize({startOnLoad:false,securityLevel:'strict',suppressErrorRendering:true,theme:'${if (dark) "dark" else "default"}',maxTextSize:64000,maxEdges:500,themeVariables:{primaryColor:'$background',primaryTextColor:'$foreground',lineColor:'$foreground'}});
    mermaid.run({querySelector:'.mermaid',suppressErrors:false}).then(()=>{clearTimeout(timeout);if(!settled){settled=true;status.remove()}}).catch(fail)}catch(e){fail()}
    </script>""" else ""
    val policy = if (diagram) "script-src 'unsafe-inline' https://rich.invalid;" else "script-src 'none';"
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; $policy style-src 'unsafe-inline'; img-src data:; font-src 'none'; connect-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'"><style>body{margin:16px;font:${16 * fontScale.coerceIn(.8f,3f)}px/1.6 sans-serif;color:$foreground;background:$background;overflow-wrap:anywhere}pre{white-space:pre-wrap}table{border-collapse:collapse}td,th{border:1px solid #888;padding:8px}svg{max-width:100%;height:auto}blockquote{border-left:1px solid #888;padding-left:12px}details{padding:8px}</style></head><body>$body$scripts</body></html>"""
}
@Composable internal fun RichSandbox(source: String, diagram: Boolean = false) {
    var fullscreen by remember(source) { mutableStateOf(false) }
    var showSource by remember(source) { mutableStateOf(false) }
    var fullscreenView by remember(source) { mutableStateOf<WebView?>(null) }
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val foreground="#%06X".format(MaterialTheme.colorScheme.onSurface.toArgb() and 0xFFFFFF)
    val background="#%06X".format(MaterialTheme.colorScheme.surfaceContainer.toArgb() and 0xFFFFFF)
    val fontScale=LocalDensity.current.fontScale
    val rendering = uiText("Rendering diagram...")
    val failed = uiText("Diagram could not be rendered. Open Source to read the original.")
    val contentKey = listOf(source,diagram,dark,foreground,background,fontScale,rendering,failed)
    val prepared by produceState<PreparedContent<Result<String>>?>(null,contentKey) { value=PreparedContent("",contentKey,withContext(Dispatchers.Default) { runCatching { richPage(source,diagram,dark,foreground,background,fontScale,rendering,failed) } }) }
    val page = prepared?.forContext("",contentKey) ?: run { LinearProgressIndicator(); return }
    Column {
        Row { TextButton(onClick = { fullscreen = true }, enabled = page.isSuccess) { Text(uiText(if(diagram) "Fullscreen diagram" else "Fullscreen card")) }; TextButton(onClick = { showSource = !showSource }) { Text(uiText(if(showSource) "Hide source" else "Source")) } }
        if(page.isFailure) Text(uiText("This preview can't be shown."), color = MaterialTheme.colorScheme.error)
        else SandboxView(page.getOrThrow(), diagram, Modifier.fillMaxWidth().height(320.dp), android.graphics.Color.parseColor(background))
        if(showSource || page.isFailure) CodeBlock(if(diagram) "mermaid" else "html", source.take(RICH_LIMIT))
    }
    if(fullscreen) Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { fullscreen = false }, modifier = Modifier.weight(1f)) { Text(uiText("Close fullscreen")) }
                IconButton(onClick = { fullscreenView?.zoomOut() }, enabled = fullscreenView != null) { Icon(Icons.Rounded.Remove, uiText("Zoom out preview")) }
                IconButton(onClick = { fullscreenView?.zoomIn() }, enabled = fullscreenView != null) { Icon(Icons.Rounded.Add, uiText("Zoom in preview")) }
            }
            Text(uiText("Pinch to zoom"), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
            SandboxView(page.getOrThrow(), diagram, Modifier.fillMaxWidth().weight(1f), android.graphics.Color.parseColor(background)) { fullscreenView = it }
        } }
    }
}
@Composable private fun SandboxView(page: String, diagram: Boolean, modifier: Modifier, backgroundColor: Int, onReady: (WebView?) -> Unit = {}) {
    var rendererFailed by remember(page) { mutableStateOf(false) }
    val description = uiText(if(diagram) "Mermaid diagram preview" else "HTML card")
    if (rendererFailed) {
        Column(modifier.padding(16.dp)) { Text(uiText("Preview stopped. Your message is unchanged.")); TextButton(onClick = { rendererFailed = false }) { Text(uiText("Retry preview")) } }
        return
    }
    AndroidView(factory = { context -> WebView(context).apply {
        setBackgroundColor(backgroundColor)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        settings.saveFormData = false; isSaveEnabled = false
        settings.javaScriptEnabled = diagram
        settings.allowFileAccess = false; settings.allowContentAccess = false
        settings.domStorageEnabled = false; settings.databaseEnabled = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.blockNetworkLoads = true; settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportZoom(true); settings.builtInZoomControls = true; settings.displayZoomControls = false
        settings.javaScriptCanOpenWindowsAutomatically = false; settings.setSupportMultipleWindows(false)
        isLongClickable = false
        contentDescription = description
        webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy(); onReady(null); rendererFailed = true
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val asset = if (!diagram) null else when (request.url.toString()) { "https://rich.invalid/mermaid.js" -> "mermaid-11.17.2.min.js"; "https://rich.invalid/polyfills.js" -> "polyfills.js"; else -> null }
                val input = asset?.let { context.assets.open("rich/$it") } ?: ByteArrayInputStream(ByteArray(0))
                return WebResourceResponse("application/javascript", "UTF-8", input)
            }
        }
        onReady(this)
        loadDataWithBaseURL("https://rich.invalid/", page, "text/html", "UTF-8", null); tag = page
    } }, update = { if(it.tag != page) { it.tag = page; it.setBackgroundColor(backgroundColor); it.loadDataWithBaseURL("https://rich.invalid/", page, "text/html", "UTF-8", null) } }, onRelease = { onReady(null); if (!rendererFailed) { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() } }, modifier = modifier.clipToBounds().testTag(if(diagram) "rich-mermaid" else "rich-html"))
}
