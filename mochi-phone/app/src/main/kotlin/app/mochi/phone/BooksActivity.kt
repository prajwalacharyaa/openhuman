package app.mochi.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.mochi.phone.ui.Icons2
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.MochiTheme
import app.mochi.phone.ui.Pill
import app.mochi.phone.ui.RoundButton
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Type
import app.mochi.phone.ui.WhileVisible
import app.mochi.phone.ui.io
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.net.URLEncoder

/**
 * December's books (PDF, EPUB, MOBI): add them, see Mark's chapter and book summaries, and start a call to have
 * Mochi read one aloud or answer about it. The server keeps the library (mochi-books, mochi-ring /books).
 */
class BooksActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MochiTheme { BooksScreen { finish() } } }
    }
}

object Books {
    fun list(c: Context): List<JSONObject> {
        val arr = Doing.get(c, "/books").optJSONArray("books") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    fun book(c: Context, id: String): JSONObject = Doing.get(c, "/books/$id")

    private val covers = java.util.concurrent.ConcurrentHashMap<String, androidx.compose.ui.graphics.ImageBitmap>()

    /** A book's cover, small, fetched once per app run (null: it has none, or it couldn't be fetched). */
    fun cover(c: Context, id: String): androidx.compose.ui.graphics.ImageBitmap? {
        covers[id]?.let { return it }
        val req = Request.Builder().url("${Mochi.ringUrl(c)}/books/$id/cover")
            .header("Authorization", "Bearer ${Mochi.token(c)}").build()
        Mochi.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val bytes = r.body?.bytes() ?: return null
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            return bmp.asImageBitmap().also { covers[id] = it }
        }
    }

    fun action(c: Context, id: String, what: String) {
        val req = Request.Builder().url("${Mochi.ringUrl(c)}/books/$id/$what")
            .header("Authorization", "Bearer ${Mochi.token(c)}")
            .post("{}".toRequestBody("application/json".toMediaType())).build()
        Mochi.http.newCall(req).execute().use { r -> if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}") }
    }

    /** Streams the file to the server (no copy in memory). Returns the error text, or null when it was taken. */
    fun upload(c: Context, uri: Uri): String? {
        var name = "book"
        var size = -1L
        c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                cur.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cur.getString(it) ?: name }
                cur.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = cur.getLong(it) }
            }
        }
        val body = object : RequestBody() {
            override fun contentType() = "text/plain".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                c.contentResolver.openInputStream(uri)?.source()?.use { sink.writeAll(it) }
            }
        }
        val req = Request.Builder().url("${Mochi.ringUrl(c)}/books/upload?name=" + URLEncoder.encode(name, "UTF-8"))
            .header("Authorization", "Bearer ${Mochi.token(c)}").post(body).build()
        Mochi.http.newCall(req).execute().use { r ->
            if (r.isSuccessful) return null
            val text = r.body?.string() ?: ""
            return runCatching { JSONObject(text).optString("error") }.getOrNull()?.ifBlank { null } ?: "HTTP ${r.code}"
        }
    }
}

@Composable
private fun Cover(id: String, hasCover: Boolean, width: androidx.compose.ui.unit.Dp) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var img by remember(id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(id, hasCover) {
        if (hasCover) io(scope, { runCatching { Books.cover(ctx, id) }.getOrNull() }) { img = it }
    }
    val shape = RoundedCornerShape(6.dp)
    val m = Modifier.width(width).aspectRatio(2f / 3f).clip(shape).background(c.field)
    val i = img
    if (i != null) androidx.compose.foundation.Image(i, contentDescription = null, modifier = m,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    else Box(m)
}

@Composable
private fun BooksScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    val books = remember { mutableStateListOf<JSONObject>() }
    var open by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    var status by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf<Int>()) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun reload() {
        io(scope, { Books.list(ctx) }, { status = "Can't reach Mochi. Is Tailscale on?" }) { list ->
            books.clear(); books.addAll(list)
            if (status.startsWith("Can't reach")) status = ""
        }
        open?.let { id -> io(scope, { Books.book(ctx, id) }) { detail = it } }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            status = "Sending the book…"
            io(scope, { Books.upload(ctx, uri) }, { status = "Not sent: ${it.message}" }) { err ->
                status = err?.let { "Not added: $it" } ?: "Added. Mochi is reading it; Mark summarizes it next."
                reload()
            }
        }
    }
    WhileVisible(open) {
        while (true) {
            reload()
            delay(20_000)
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            RoundButton(Icons2.Back, "Back", c.field, c.ink, 44.dp) {
                if (open != null) { open = null; detail = null; expanded = emptySet(); confirmDelete = false } else onBack()
            }
            Text(if (open == null) "Books" else (detail?.optString("title") ?: "Book"), style = Type.display, color = c.ink,
                maxLines = 2)
        }
        if (status.isNotEmpty()) Text(status, style = Type.small, color = c.muted,
            modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.xs))
        val d = detail
        if (open == null) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = Space.l, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card).padding(Space.l),
                        verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Text("Add books", style = Type.title, color = c.ink)
                        Text("PDF, EPUB or MOBI, up to 150 MB. Mark summarizes every chapter and the whole book; on a call, " +
                            "ask Mochi to read from it or about any chapter. Copy-protected Kindle books and scanned PDFs " +
                            "can't be read.", style = Type.small, color = c.muted)
                        Pill("Choose books", true) {
                            pick.launch(arrayOf("application/pdf", "application/epub+zip", "application/x-mobipocket-ebook",
                                "application/vnd.amazon.ebook", "application/octet-stream"))
                        }
                    }
                }
                if (books.isEmpty()) item { Text("No books yet.", style = Type.body, color = c.muted) }
                items(books, key = { it.optString("id") }) { b ->
                    val chapters = b.optInt("chapters")
                    val done = b.optInt("chapter_summaries")
                    val state = when {
                        b.optBoolean("book_summary") -> "Summarized"
                        done > 0 -> "Mark is summarizing: $done of $chapters chapters"
                        else -> "Waiting for Mark"
                    }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card)
                        .clickable(role = Role.Button) { open = b.optString("id"); detail = null }.padding(Space.l),
                        horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    Cover(b.optString("id"), b.optBoolean("cover"), 52.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text(b.optString("title"), style = Type.title, color = c.ink)
                        val author = b.optString("author").takeIf { it.isNotBlank() && it != "null" }
                        Text(listOfNotNull(author, "$chapters chapters", b.optString("format").uppercase()).joinToString(" · "),
                            style = Type.small, color = c.muted)
                        Text(state, style = Type.small, color = if (b.optBoolean("book_summary")) c.greenInk else c.muted)
                    }
                    }
                }
            }
        } else if (d == null) {
            Text("Opening…", style = Type.body, color = c.muted, modifier = Modifier.padding(Space.xl))
        } else {
            val list = d.optJSONArray("chapter_list")
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = Space.l, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.CenterVertically) {
                        Cover(d.optString("id"), d.optBoolean("cover"), 96.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            val author = d.optString("author").takeIf { it.isNotBlank() && it != "null" }
                            if (author != null) Text(author, style = Type.body, color = c.ink)
                            Text("${d.optInt("chapters")} parts · ${d.optInt("words")} words", style = Type.small, color = c.muted)
                            val cur = d.optJSONObject("current")
                            if (cur != null) Text("Last read: part ${cur.optInt("chapter")}" +
                                (cur.optInt("page").takeIf { it > 0 }?.let { ", page $it" } ?: ""), style = Type.small, color = c.muted)
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Pill("Read with Mochi", true) { MainActivity.callMochi(ctx) }
                        Pill("Ask Mark", false) {
                            // the newest chat with Mark is a network look-up: off the main thread
                            io(scope, { runCatching { Agents.latestChat(ctx, "mark") }.getOrNull() }) { thread ->
                                ctx.startActivity(Intent(ctx, ChatActivity::class.java)
                                    .putExtra(ChatActivity.EXTRA_AGENT, "mark")
                                    .apply { if (thread != null) putExtra(ChatActivity.EXTRA_THREAD, thread) }
                                    .putExtra(ChatActivity.EXTRA_TEXT, "About the book \"${d.optString("title")}\": "))
                            }
                        }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card).padding(Space.l),
                        verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Text("The whole book", style = Type.title, color = c.ink)
                        val s = d.optString("summary").takeIf { it.isNotBlank() && it != "null" }
                        Text(s ?: "Mark writes this after the chapter summaries.", style = Type.body,
                            color = if (s != null) c.ink else c.muted)
                    }
                }
                item { Text("Chapters", style = Type.title, color = c.ink) }
                val n = list?.length() ?: 0
                items((0 until n).toList()) { i ->
                    val ch = list!!.getJSONObject(i)
                    val num = ch.optInt("n")
                    val sum = ch.optString("summary").takeIf { it.isNotBlank() && it != "null" }
                    val isOpen = num in expanded
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card)
                        .clickable(role = Role.Button) { expanded = if (isOpen) expanded - num else expanded + num }
                        .padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text("$num. ${ch.optString("title")}", style = Type.body.copy(fontWeight = Type.strong), color = c.ink)
                        if (isOpen) Text(sum ?: "Not summarized yet.", style = Type.body, color = if (sum != null) c.ink else c.muted)
                        else if (sum == null) Text("Summary coming", style = Type.small, color = c.muted)
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                        Pill("Summarize again", false) {
                            val id = open ?: return@Pill
                            io(scope, { Books.action(ctx, id, "summarize") }, { status = "Not sent: ${it.message}" }) {
                                status = "Mark is summarizing it again."
                            }
                        }
                        if (!confirmDelete) Pill("Delete", false) { confirmDelete = true }
                        else Pill("Delete for good", true) {
                            val id = open ?: return@Pill
                            io(scope, { Books.action(ctx, id, "delete") }, { status = "Not deleted: ${it.message}" }) {
                                status = "Deleted."; open = null; detail = null; confirmDelete = false; reload()
                            }
                        }
                    }
                }
                item { Box(Modifier.height(Space.xl)) }
            }
        }
    }
}
