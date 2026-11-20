package app.mochi.phone

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.mochi.phone.ui.Field
import app.mochi.phone.ui.Icons2
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.MochiTheme
import app.mochi.phone.ui.Pill
import app.mochi.phone.ui.RoundButton
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Type
import app.mochi.phone.ui.io
import androidx.compose.ui.unit.dp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * The room (the Roast Room on the server, mochi-room): the room's own page, whole, in the app (the live table,
 * past sessions, verdicts, the PDF), plus "Ask the room" to put a decision to it, quick or full. Only December's
 * words go in; the server holds the meeting apart from Mochi and a notification brings the verdict.
 */
class RoomActivity : ComponentActivity() {
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = web
                if (w != null && w.canGoBack()) w.goBack() else finish()
            }
        })
        setContent { MochiTheme { RoomScreen({ web = it }) { finish() } } }
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }
}

object Room {
    fun url(c: Context) = "https://${Mochi.host(c)}:8453/"

    /** Queues a decision (mochi-ring POST /room). Returns the error text, or null when the room took it. */
    fun ask(c: Context, decision: String, full: Boolean): String? {
        val body = JSONObject().put("decision", decision).put("mode", if (full) "full" else "quick").toString()
        val req = Request.Builder().url("${Mochi.ringUrl(c)}/room")
            .header("Authorization", "Bearer ${Mochi.token(c)}")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        Mochi.http.newCall(req).execute().use { r ->
            if (r.isSuccessful) return null
            val text = r.body?.string() ?: ""
            return runCatching { JSONObject(text).optString("error") }.getOrNull()?.ifBlank { null } ?: "HTTP ${r.code}"
        }
    }

    /** The room's "Download PDF" makes the file in the page (a blob: link); this saves it to Downloads and opens it. */
    class Saver(private val c: Context) {
        @JavascriptInterface
        fun save(base64: String, name: String) {
            val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
            if (bytes == null || bytes.isEmpty()) { toast("Couldn't read the PDF from the room"); return }
            val file = name.replace(Regex("[^A-Za-z0-9._ -]"), "").ifBlank { "room-verdict.pdf" }
                .let { if (it.endsWith(".pdf")) it else "$it.pdf" }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            }
            val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null || runCatching { c.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }.isFailure) {
                toast("Couldn't save the PDF")
                return
            }
            toast("Saved to Downloads: $file")
            runCatching {
                c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        private fun toast(text: String) =
            android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(c, text, Toast.LENGTH_LONG).show() }
    }

    /** Reads a blob: link inside the page and hands it to [Saver] (WebView can't download blob: links itself). */
    fun saveBlobJs(url: String) = """
        (async () => {
          try {
            const b = await (await fetch(${JSONObject.quote(url)})).blob();
            const r = new FileReader();
            r.onloadend = () => MochiRoom.save(String(r.result).split(',')[1] || '', 'room-verdict-' + Date.now() + '.pdf');
            r.readAsDataURL(b);
          } catch (e) { MochiRoom.save('', ''); }
        })();
    """.trimIndent()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RoomScreen(onWeb: (WebView) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var decision by remember { mutableStateOf("") }
    var full by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            RoundButton(Icons2.Back, "Back", c.field, c.ink, 44.dp) { onBack() }
            Text("The room", style = Type.display, color = c.ink, modifier = Modifier.weight(1f))
            Pill(if (asking) "Close" else "Ask the room", !asking, height = 40.dp) { asking = !asking; status = "" }
        }
        if (asking) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.xs),
                verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text("Your decision in your own words: the options, your numbers (revenue, cash, team), your limits, " +
                    "what success looks like and which way you lean. Nothing else goes in.", style = Type.small, color = c.muted)
                Field(decision, { decision = it }, "Should I…")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Pill("Room", !full, Modifier.weight(1f), height = 40.dp) { full = false }
                    Pill("Full room", full, Modifier.weight(1f), height = 40.dp) { full = true }
                    RoundButton(Icons2.Send, "Send to the room", c.ink, c.bg, 40.dp) {
                        val words = decision.trim()
                        if (sending) return@RoundButton
                        if (words.length < 15) { status = "Say a bit more: the options and your numbers."; return@RoundButton }
                        sending = true
                        status = "Sending…"
                        io(scope, { Room.ask(ctx, words, full) }, { sending = false; status = "Couldn't reach the server." }) { err ->
                            sending = false
                            if (err == null) {
                                status = if (full) "The full room is meeting: about 30 to 60 minutes. Watch it below; " +
                                    "a notification brings the verdict." else "The room is meeting: about 10 to 20 minutes. " +
                                    "Watch it below; a notification brings the verdict."
                                decision = ""
                                asking = false
                            } else status = err
                        }
                    }
                }
                Text(if (full) "Full room: the whole board, three rounds, the detailed report. For big calls that are hard to undo."
                    else "Room: four advisors, two rounds. For everyday and medium decisions.", style = Type.small, color = c.muted)
            }
        }
        if (status.isNotEmpty()) Text(status, style = Type.small, color = c.muted,
            modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.xs))
        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                addJavascriptInterface(Room.Saver(context.applicationContext), "MochiRoom")
                val home = Uri.parse(Room.url(context))
                webViewClient = object : WebViewClient() {
                    // the room stays in here; any other link opens in the browser
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url
                        if (u.host == home.host && u.port == home.port) return false
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        return true
                    }
                }
                setDownloadListener { url, _, _, _, _ ->
                    if (url.startsWith("blob:")) evaluateJavascript(Room.saveBlobJs(url), null)
                    else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                loadUrl(Room.url(context))
                onWeb(this)
            }
        })
    }
}
