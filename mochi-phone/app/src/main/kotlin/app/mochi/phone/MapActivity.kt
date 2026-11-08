package app.mochi.phone

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.mochi.phone.ui.Field
import app.mochi.phone.ui.Icons2
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.Mascot
import app.mochi.phone.ui.MochiTheme
import app.mochi.phone.ui.Mood
import app.mochi.phone.ui.Pill
import app.mochi.phone.ui.RoundButton
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Type
import app.mochi.phone.ui.io
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * A project's map (its Graphify graph): pinch to zoom, drag to move, tap a node to focus it, see what it connects
 * to and bring its neighbours in. Starts with the main points (the most connected parts). Drawn by the bundled
 * vis-network in a WebView that may load nothing but its own two files.
 */
class MapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = intent.getStringExtra(EXTRA_REPO) ?: return finish()
        setContent { MochiTheme { MapScreen(repo) { finish() } } }
    }

    companion object {
        const val EXTRA_REPO = "repo"
        fun open(c: Context, repo: String) = c.startActivity(Intent(c, MapActivity::class.java).putExtra(EXTRA_REPO, repo))
    }
}

/** What the map page tells the app (it calls these on a background thread). Public: WebView calls it by reflection. */
class MapBridge(private val json: String, private val onSelect: (String) -> Unit,
                private val onReady: (String) -> Unit, private val onMissing: (String) -> Unit,
                private val onFailed: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface fun graph(): String = json
    @JavascriptInterface fun select(details: String) { main.post { onSelect(details) } }
    @JavascriptInterface fun ready(nodes: String, edges: String) { main.post { onReady("$nodes parts · $edges links") } }
    @JavascriptInterface fun notFound(text: String) { main.post { onMissing(text) } }
    @JavascriptInterface fun failed(message: String) { main.post { onFailed(message) } }
}

private fun js(s: String) = JSONObject.quote(s)

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MapScreen(repo: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val dark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    var graph by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var web by remember { mutableStateOf<WebView?>(null) }
    fun run(script: String) = web?.evaluateJavascript(script, null)

    LaunchedEffect(repo) {
        io(scope, { Mochi.graph(ctx, repo) }, { error = "Couldn't load the map: ${it.message}" }) { graph = it }
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        val json = graph
        if (json != null) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.allowContentAccess = false
                    settings.setSupportZoom(false) // the map zooms itself (pinch)
                    webViewClient = object : WebViewClient() {
                        // nothing but the bundled page and library: no network, no other files
                        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                            val url = req.url.toString()
                            return if (url.startsWith("file:///android_asset/map/")) null
                            else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest) = true
                    }
                    // the page's messages and errors go to the phone's log (adb logcat -s MochiMap)
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            Log.d("MochiMap", "${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                            return true
                        }
                    }
                    addJavascriptInterface(MapBridge(json,
                        onSelect = { d -> selected = if (d.isEmpty()) null else runCatching { JSONObject(d) }.getOrNull() },
                        onReady = { summary = it },
                        onMissing = { error = "Nothing called \"$it\" in this map." },
                        onFailed = { error = "The map couldn't draw: $it" }), "Android")
                    loadUrl("file:///android_asset/map/index.html" + if (dark) "?dark=1" else "")
                    web = this
                }
            }, onRelease = { it.destroy() })
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Mascot(Modifier.size(96.dp), mood = if (error.isEmpty()) Mood.Thinking else Mood.Idle)
                Text(if (error.isEmpty()) "Drawing the map…" else error, style = Type.body, color = c.muted)
            }
        }

        // top: back, name, search, main points
        Column(Modifier.fillMaxWidth().systemBarsPadding().imePadding().padding(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                RoundButton(Icons2.Back, "Back", c.field, c.ink, 44.dp, onBack)
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(c.bg.copy(alpha = 0.85f))
                    .padding(horizontal = Space.m, vertical = Space.xs)) {
                    Text(repo, style = Type.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (summary.isNotEmpty()) Text(summary, style = Type.small, color = c.muted)
                }
                RoundButton(Icons2.Search, "Find a part", c.field, c.ink, 44.dp) { searching = !searching }
            }
            if (searching) Field(query, { query = it }, "Find a part (e.g. Navbar, api, auth)", Modifier.fillMaxWidth(),
                singleLine = true) {
                if (query.isNotBlank()) RoundButton(Icons2.Search, "Find", c.ink, c.bg, 40.dp) {
                    error = ""; run("find(${js(query.trim())})")
                }
            }
            if (error.isNotEmpty() && graph != null) Text(error, style = Type.small, color = c.danger,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.bg).padding(Space.s))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Pill("Main points", false, Modifier.background(c.bg, RoundedCornerShape(18.dp)), height = 36.dp) {
                    selected = null; run("mainPoints()")
                }
            }
        }

        // bottom: the tapped part and what it connects to
        selected?.let { d -> NodePanel(d, Modifier.align(Alignment.BottomCenter),
            onOpen = { id -> run("focusNode(${js(id)})") },
            onAll = { run("expandAll(${js(d.optString("id"))})") },
            onClose = { selected = null }) }
    }
}

@Composable
private fun NodePanel(d: JSONObject, modifier: Modifier, onOpen: (String) -> Unit, onAll: () -> Unit, onClose: () -> Unit) {
    val c = LocalMochi.current
    val conns = d.optJSONArray("connections")
    Column(modifier.fillMaxWidth().systemBarsPadding().padding(Space.m).shadow(12.dp, RoundedCornerShape(24.dp))
        .clip(RoundedCornerShape(24.dp)).background(c.bg).padding(Space.l).heightIn(max = 380.dp),
        verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(d.optString("label"), style = Type.title, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val kind = listOf(d.optString("type"), d.optString("group")).filter { it.isNotBlank() }.joinToString(" · ")
                if (kind.isNotEmpty()) Text(kind, style = Type.small, color = c.violetInk)
                val file = d.optString("file")
                if (file.isNotBlank()) Text(file + d.optString("where").let { if (it.isBlank()) "" else " · $it" },
                    style = Type.small, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            RoundButton(Icons2.Close, "Close", c.field, c.ink, 36.dp, onClose)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${d.optInt("total")} connections", style = Type.small.copy(fontWeight = Type.strong), color = c.ink,
                modifier = Modifier.weight(1f))
            Text("Show all on the map", style = Type.small, color = c.blueInk,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onAll)
                    .padding(Space.s))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            items((0 until (conns?.length() ?: 0)).mapNotNull { conns?.optJSONObject(it) }) { x ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.field)
                    .clickable { onOpen(x.optString("id")) }.padding(horizontal = Space.m, vertical = Space.s),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (x.optBoolean("out")) "→" else "←", style = Type.body, color = c.muted,
                        modifier = Modifier.padding(end = Space.s))
                    Column(Modifier.weight(1f)) {
                        Text(x.optString("label"), style = Type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(x.optString("rel"), style = Type.small, color = c.muted)
                    }
                }
            }
        }
    }
}
