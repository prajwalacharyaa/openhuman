package app.mochi.phone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mochi.phone.ui.Field
import app.mochi.phone.ui.Icons2
import app.mochi.phone.ui.LineIcon
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.MarkdownText
import app.mochi.phone.ui.Mascot
import app.mochi.phone.ui.MochiTheme
import app.mochi.phone.ui.Mood
import app.mochi.phone.ui.RoundButton
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.StopButton
import app.mochi.phone.ui.Type
import app.mochi.phone.ui.io
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * A conversation with Mochi: the same thread the calls and the desktop app use. Replies stream in live (with what
 * Mochi is doing), formatted; Stop cancels a reply; photos and files can be attached; Quick / Normal / Deep picks
 * the model (Normal lets Mochi decide). Opened from Home, a notification, the "New chat" shortcut or Share.
 */
class ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val fresh = savedInstanceState == null
        val newChat = fresh && (intent.getBooleanExtra(EXTRA_NEW, false) || intent.action == ACTION_NEW_CHAT ||
            intent.action == Intent.ACTION_SEND)
        val text = if (fresh) intent.getStringExtra(EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_TEXT) else null
        val sendNow = fresh && intent.getBooleanExtra(EXTRA_SEND, false)
        @Suppress("DEPRECATION")
        val shared = if (fresh) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) else null
        val thread = intent.getStringExtra(EXTRA_THREAD)
        setContent { MochiTheme { ChatScreen(thread, newChat, text, sendNow, shared) { finish() } } }
    }

    companion object {
        const val EXTRA_NEW = "new"
        const val EXTRA_THREAD = "thread"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SEND = "send"
        const val ACTION_NEW_CHAT = "app.mochi.phone.NEW_CHAT"
        /** The prefix mochi-ask adds for Claude; not something December said. */
        val VOICE_ASK_NOTE = Regex("""^\(Asked on a voice call[^)]*\)\s*""")
        val ATTACHMENT = Regex("""\[(ATTACHMENT|IMAGE|FILE):[^\]]*]""")
    }
}

/** One thing Mochi did while answering. */
data class Step(val label: String, val done: Boolean)

/** Plain words for a tool name. */
fun stepLabel(tool: String): String {
    val t = tool.lowercase()
    return when {
        "search" in t && "memory" !in t -> "Searching the web"
        "fetch" in t || "browse" in t -> "Reading a page"
        "memory" in t || "recall" in t -> "Checking memory"
        t == "bash" || "shell" in t -> "Running a command"
        t == "read" || "file_read" in t || t == "grep" || t == "glob" -> "Reading files"
        t == "edit" || t == "write" || "patch" in t -> "Editing files"
        t == "task" || t == "agent" || "subagent" in t -> "Working with a helper"
        t == "skill" -> "Using a skill"
        t.isBlank() -> "Working"
        else -> tool.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

private fun visible(m: JSONObject): Boolean {
    val text = m.optString("content").trim()
    return text.isNotEmpty() && !text.startsWith("(December picked up the phone call")
}

@Composable
fun ChatScreen(initialThread: String?, newChat: Boolean, initialText: String?, sendNow: Boolean, shared: Uri?,
               onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var thread by remember { mutableStateOf(if (newChat) null else initialThread ?: Mochi.thread(ctx)) }
    var title by remember { mutableStateOf("Chat") }
    val messages = remember { mutableStateListOf<JSONObject>() }
    val steps = remember { mutableStateListOf<Step>() }
    val files = remember { mutableStateListOf<Attachment>() }
    var streaming by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf(if (sendNow) "" else initialText.orEmpty()) }
    var mode by remember { mutableStateOf(Mochi.mode(ctx)) }
    var status by remember { mutableStateOf("") }
    var live by remember { mutableStateOf<Live?>(null) }
    var attachMenu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    // the call state isn't Compose state: look again every second (for the Call / Silent button)
    var onThisCall by remember { mutableStateOf(false) }
    var silent by remember { mutableStateOf(Call.silent) }
    LaunchedEffect(thread) {
        while (true) {
            onThisCall = Call.active && thread != null && Call.thread == thread
            silent = Call.silent
            delay(1_000)
        }
    }

    fun reload() {
        val id = thread ?: return
        io(scope, { Mochi.messages(ctx, id) }, { status = "Can't reach Mochi. Is Tailscale on?" }) { all ->
            val shown = (0 until all.length()).mapNotNull { all.optJSONObject(it) }.filter(::visible)
            messages.clear(); messages.addAll(shown)
            if (status.startsWith("Can't reach")) status = ""
            val last = shown.lastOrNull()
            if (busy && live?.connected != true && last != null && last.optString("sender") != "user") busy = false
        }
    }

    fun send(text: String) {
        val body = Attachments.message(text, files)
        val id = thread
        if (body.isBlank() || id == null) return
        messages.add(JSONObject().put("id", "local-${System.nanoTime()}").put("sender", "user")
            .put("content", text.ifBlank { "(attachment)" }))
        input = ""
        val sending = files.toList()
        files.clear()
        busy = true; streaming = ""; steps.clear(); status = ""
        io(scope, { Mochi.send(ctx, id, body, model = Mochi.modelFor(mode)) },
            { busy = false; files.addAll(sending); status = "Not sent: can't reach Mochi. (${it.message})" }) { reload() }
    }

    // start or open the conversation
    LaunchedEffect(Unit) {
        val id = thread
        if (id == null) {
            status = "Starting a new conversation…"
            io(scope, { Mochi.newThread(ctx) }, { status = "Can't reach Mochi. Is Tailscale on?" }) { newId ->
                thread = newId
                Mochi.setThread(ctx, newId)
                status = ""
                if (sendNow && !initialText.isNullOrBlank()) send(initialText)
            }
        } else {
            Mochi.setThread(ctx, id)
        }
        if (shared != null) io(scope, { Attachments.load(ctx, shared) }) { a -> if (a != null) files.add(a) }
    }

    // live events for this conversation (and a slow re-read for what calls and the desktop add)
    DisposableEffect(thread) {
        val id = thread
        val l = id?.let {
            Live(ctx, it) { name, data ->
                when (name) {
                    "inference_start", "iteration_start" -> busy = true
                    "text_delta" -> if (data.optString("delta_kind", "text") == "text") {
                        busy = true; streaming += data.optString("delta")
                    }
                    "tool_call" -> { busy = true; steps.add(Step(stepLabel(data.optString("tool_name")), false)) }
                    "tool_result" -> {
                        val i = steps.indexOfLast { !it.done }
                        if (i >= 0) steps[i] = steps[i].copy(done = true)
                    }
                    "chat_done" -> { busy = false; streaming = ""; steps.clear(); reload() }
                    "chat_error" -> {
                        busy = false; streaming = ""; steps.clear()
                        val msg = data.optString("message")
                        if (msg.isNotBlank() && !msg.contains("cancel", ignoreCase = true)) status = msg
                        reload()
                    }
                }
            }.also { it.open() }
        }
        live = l
        reload()
        if (id != null) io(scope, { Mochi.threads(ctx).firstOrNull { it.optString("id") == id } }) { t ->
            t?.optString("title")?.takeIf { it.isNotBlank() }?.let { title = it }
        }
        onDispose { l?.close() }
    }
    LaunchedEffect(thread) {
        while (true) {
            delay(if (live?.connected == true) 20_000 else 6_000)
            if (!busy || live?.connected != true) reload()
        }
    }
    LaunchedEffect(messages.size, streaming.length, steps.size, busy) {
        val count = messages.size + (if (busy) 1 else 0)
        if (count > 0) list.animateScrollToItem(count - 1)
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        uris.forEach { u -> io(scope, { Attachments.load(ctx, u) }) { a -> if (a != null && files.size < 4) files.add(a) } }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) io(scope, { Attachments.load(ctx, u) }) { a ->
            if (a != null) files.add(a) else status = "That file type or size isn't supported."
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding().imePadding()) {
        // header: back, title, Mochi (thinks while working, talks while writing)
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons2.Back, "Back", c.field, c.ink, 44.dp, onBack)
            Text(title, style = Type.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = Space.m))
            Mascot(Modifier.size(44.dp), onTap = { DoingActivity.open(ctx) }, mood = when {
                streaming.isNotEmpty() -> Mood.Speaking
                busy -> Mood.Thinking
                else -> Mood.Idle
            })
        }
        if (status.isNotEmpty()) Text(status, style = Type.small, color = c.danger,
            modifier = Modifier.padding(horizontal = Space.l, vertical = Space.xs))

        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.l)) {
            if (messages.isEmpty() && !busy) item("empty") {
                Column(Modifier.fillMaxWidth().padding(top = Space.xxl), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Mascot(Modifier.size(96.dp))
                    Text("What can I do for you?", style = Type.title, color = c.ink)
                    Text("Type, attach a photo or file, or pick Quick or Deep below.", style = Type.small, color = c.muted)
                }
            }
            items(messages) { m -> Bubble(m) }
            if (busy) item("live") { LiveReply(steps, streaming) }
        }

        // composer: attachments, mode, field, send / stop (all in thumb reach)
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (files.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                items(files.toList()) { a -> AttachmentChip(a) { files.remove(a) } }
            }
            ModeChips(mode) { mode = it; Mochi.setMode(ctx, it) }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Box {
                    RoundButton(Icons2.Plus, "Attach photo or file", c.field, c.ink) { attachMenu = true }
                    DropdownMenu(attachMenu, { attachMenu = false }) {
                        DropdownMenuItem({ Text("Photo") }, {
                            attachMenu = false
                            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                        DropdownMenuItem({ Text("File (PDF, text, document)") }, {
                            attachMenu = false; pickFile.launch(arrayOf("*/*"))
                        })
                    }
                }
                Field(input, { input = it }, "Message Mochi", Modifier.weight(1f))
                if (busy) {
                    StopButton {
                        thread?.let { id ->
                            io(scope, { Mochi.cancel(ctx, id) }) { busy = false; streaming = ""; steps.clear(); reload() }
                        }
                    }
                } else {
                    // on this chat's call: Silent (Mochi answers in text only); otherwise: call Mochi in this chat
                    if (onThisCall) {
                        RoundButton(if (silent) Icons2.Muted else Icons2.Speaker,
                            if (silent) "Silent: Mochi answers in text. Tap for voice" else "Mochi talks. Tap for silent",
                            if (silent) c.ink else c.field, if (silent) c.bg else c.ink) {
                            Call.silent = !Call.silent; silent = Call.silent
                        }
                    } else if (thread != null) {
                        RoundButton(Icons2.Phone, "Call Mochi in this chat", c.field, c.ink) {
                            thread?.let { Mochi.setThread(ctx, it) }
                            MainActivity.callMochi(ctx)
                        }
                    }
                    RoundButton(Icons2.Send, "Send", c.ink, c.bg) { send(input) }
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: JSONObject) {
    val c = LocalMochi.current
    val user = m.optString("sender") == "user"
    val voice = m.optJSONObject("extraMetadata")?.optString("source") == "voice"
    val raw = m.optString("content").trim().replace(ChatActivity.VOICE_ASK_NOTE, "")
    val attached = ChatActivity.ATTACHMENT.findAll(raw).count()
    val text = raw.replace(ChatActivity.ATTACHMENT, "").trim()
    val note = if (user) c.onUserBubble.copy(alpha = 0.7f) else c.muted
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 320.dp)
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = if (user) 22.dp else 6.dp,
                bottomEnd = if (user) 6.dp else 22.dp))
            .background(if (user) c.userBubble else c.card).padding(horizontal = Space.l, vertical = Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (voice) Text("On a call", style = Type.small, color = note)
            if (attached > 0) Text(if (attached == 1) "1 attachment" else "$attached attachments", style = Type.small, color = note)
            if (text.isNotEmpty()) {
                if (user) Text(text, style = Type.body, color = c.onUserBubble) else MarkdownText(text, c.ink)
            }
        }
    }
}

/** The reply being written: what Mochi is doing, then the text so far. */
@Composable
private fun LiveReply(steps: List<Step>, text: String) {
    val c = LocalMochi.current
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (steps.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            items(steps.takeLast(4)) { s ->
                Text(if (s.done) "✓ ${s.label}" else "${s.label}…", style = Type.small,
                    color = if (s.done) c.greenInk else c.blueInk,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (s.done) c.greenBg else c.blueBg)
                        .padding(horizontal = Space.m, vertical = 6.dp))
            }
        }
        Box(Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)).background(c.card)
            .padding(horizontal = Space.l, vertical = Space.m)) {
            if (text.isEmpty()) Text("Thinking…", style = Type.body, color = c.muted)
            else MarkdownText("$text▍", c.ink)
        }
    }
}

@Composable
private fun ModeChips(mode: String, onPick: (String) -> Unit) {
    val c = LocalMochi.current
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        for ((key, label) in listOf("quick" to "Quick", "normal" to "Normal", "deep" to "Deep")) {
            val on = mode == key
            Box(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(if (on) c.ink else c.field)
                .clickable(role = Role.RadioButton) { onPick(key) }
                .semantics { selected = on; contentDescription = "$label mode" }
                .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(label, style = Type.small.copy(fontWeight = if (on) Type.strong else null),
                    color = if (on) c.bg else c.ink)
            }
        }
    }
}

@Composable
private fun AttachmentChip(a: Attachment, onRemove: () -> Unit) {
    val c = LocalMochi.current
    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(c.field).padding(Space.xs),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        val thumb = a.thumb
        if (thumb != null) Image(thumb.asImageBitmap(), a.name, Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)))
        else Text(a.name, style = Type.small, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp).padding(start = Space.s))
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onRemove)
            .semantics { contentDescription = "Remove ${a.name}" }, contentAlignment = Alignment.Center) {
            LineIcon(Icons2.Close, c.muted, 16.dp)
        }
    }
}
