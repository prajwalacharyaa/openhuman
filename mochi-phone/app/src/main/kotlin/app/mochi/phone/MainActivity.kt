package app.mochi.phone

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mochi.phone.ui.Field
import app.mochi.phone.ui.Icons2
import app.mochi.phone.ui.Label
import app.mochi.phone.ui.LineIcon
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.Mascot
import app.mochi.phone.ui.MochiTheme
import app.mochi.phone.ui.Pill
import app.mochi.phone.ui.RoundButton
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Tile
import app.mochi.phone.ui.Type
import app.mochi.phone.ui.io
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.time.Instant

/**
 * Home: everything one tap away. Decisions to answer right on their card, an "Ask Mochi anything" bar (a new chat
 * with no extra screen), tiles for the latest chat, the latest notice, Memory and Inbox, and Call Mochi at the
 * bottom. Also the first-time setup (server + Mochi's token) and the permissions a ringing phone needs.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO), 1)
        }
        if (savedInstanceState == null && intent.action == ACTION_CALL) callMochi(this)
        val start = intent.getStringExtra(EXTRA_TAB) ?: "home"
        setContent { MochiTheme { App(start) } }
    }

    override fun onResume() {
        super.onResume()
        RingService.start(this)
        WakeService.start(this) // listening for "Mochi" again after a reboot, if it's on
    }

    companion object {
        const val ACTION_CALL = "app.mochi.phone.CALL"
        const val EXTRA_TAB = "tab"

        fun callMochi(c: Context) {
            if (Mochi.configured(c)) {
                c.startActivity(Intent(c, CallActivity::class.java).putExtra(CallActivity.EXTRA_ANSWER, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        fun openChat(c: Context, thread: String? = null, new: Boolean = false, text: String? = null, send: Boolean = false) {
            c.startActivity(Intent(c, ChatActivity::class.java).apply {
                if (thread != null) putExtra(ChatActivity.EXTRA_THREAD, thread)
                putExtra(ChatActivity.EXTRA_NEW, new)
                if (text != null) putExtra(ChatActivity.EXTRA_TEXT, text)
                putExtra(ChatActivity.EXTRA_SEND, send)
            })
        }
    }
}

@Composable
private fun App(start: String) {
    var tab by remember { mutableStateOf(start) }
    BackHandler(enabled = tab != "home") { tab = "home" }
    when (tab) {
        "chats" -> ChatsScreen { tab = "home" }
        "memory" -> MemoryScreen { tab = "home" }
        "inbox" -> InboxScreen { tab = "home" }
        else -> HomeScreen { tab = it }
    }
}

/** A permission or setting a ringing phone needs, with where to fix it. */
private data class Fix(val label: String, val intent: Intent)

private fun fixes(c: Context): List<Fix> {
    val out = mutableListOf<Fix>()
    val nm = c.getSystemService(NotificationManager::class.java)
    val power = c.getSystemService(PowerManager::class.java)
    if (!nm.areNotificationsEnabled()) out += Fix("Allow notifications (needed to ring)",
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName))
    if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) out += Fix("Allow full-screen calls",
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${c.packageName}")))
    if (!power.isIgnoringBatteryOptimizations(c.packageName)) out += Fix("Let Mochi run in the background",
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}")))
    return out
}

private fun greeting(): String {
    val h = java.time.LocalTime.now().hour
    return when {
        h < 5 -> "Good night"
        h < 12 -> "Good morning"
        h < 17 -> "Good afternoon"
        else -> "Good evening"
    } + ", December"
}

private fun ago(iso: String): String = runCatching {
    DateUtils.getRelativeTimeSpanString(Instant.parse(iso).toEpochMilli()).toString()
}.getOrDefault("")

@Composable
private fun HomeScreen(go: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var configured by remember { mutableStateOf(Mochi.configured(ctx)) }
    var approvals by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var latest by remember { mutableStateOf<JSONObject?>(null) }
    var notice by remember { mutableStateOf(Mochi.inbox(ctx).firstOrNull()) }
    var ram by remember { mutableStateOf("") }
    var ask by remember { mutableStateOf("") }
    var fixList by remember { mutableStateOf(fixes(ctx)) }
    var showSetup by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(setOf<String>()) }

    fun refresh() {
        if (!Mochi.configured(ctx)) return
        io(scope, { Mochi.approvals(ctx) }) { approvals = it }
        io(scope, { Mochi.threads(ctx).firstOrNull() }) { latest = it }
        io(scope, { Mochi.ramLabel(ctx) }) { ram = it }
        notice = Mochi.inbox(ctx).firstOrNull()
        fixList = fixes(ctx)
    }
    LaunchedEffect(configured) {
        while (true) { refresh(); delay(10_000) }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) ctx.startActivity(Intent(ctx, ChatActivity::class.java).setAction(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_STREAM, u).putExtra(ChatActivity.EXTRA_TEXT, ask))
    }

    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m)) {
            // greeting, connection, Mochi
            Row(Modifier.fillMaxWidth().padding(top = Space.xl), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(when {
                        !configured -> "Set up Mochi to start"
                        RingService.connected -> listOf("Connected", ram).filter { it.isNotEmpty() }.joinToString(" · ")
                        else -> "Not connected. Is Tailscale on?"
                    }, style = Type.small, color = c.muted)
                    Text(greeting(), style = Type.display, color = c.ink)
                }
                Mascot(Modifier.size(72.dp), onTap = {})
            }
            if (!configured || showSetup) SetupCard { configured = true; showSetup = false }
            for (f in fixList) {
                Text(f.label, style = Type.small.copy(fontWeight = Type.strong), color = c.amberInk,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.amberBg)
                        .clickable(role = Role.Button) { ctx.startActivity(f.intent) }.padding(Space.l))
            }
            // decisions, answered right here
            for (a in approvals) {
                val id = a.optString("id")
                val approve = a.optString("approve_url")
                val isCall = approve.isEmpty()
                val replyThread = a.optString("thread_id")
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.amberBg)
                    .clickable(enabled = replyThread.isNotEmpty()) { MainActivity.openChat(ctx, replyThread) }.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Text(if (isCall) (if (a.optString("result") == "declined") "Declined call" else "Missed call")
                        else "Waiting for you", style = Type.small.copy(fontWeight = Type.strong), color = c.amberInk)
                    Text(if (isCall) a.optString("question") else a.optString("title", "Approval"),
                        style = Type.title, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (!isCall && a.optString("text").isNotBlank()) Text(a.optString("text"), style = Type.small,
                        color = c.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (replyThread.isNotEmpty()) Text("Tap to answer by typing", style = Type.small, color = c.amberInk)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        val off = id in busy
                        if (isCall) {
                            Pill("Call back", true, Modifier.weight(1f)) {
                                ctx.startActivity(Intent(ctx, CallActivity::class.java)
                                    .putExtra(CallActivity.EXTRA_CALL, a.toString()).putExtra(CallActivity.EXTRA_ANSWER, true))
                            }
                            Pill("Dismiss", false, Modifier.weight(1f)) {
                                busy = busy + id
                                io(scope, { Mochi.dismissCall(ctx, id) }, { busy = busy - id }) { busy = busy - id; refresh() }
                            }
                        } else {
                            Pill(if (off) "…" else "Approve", true, Modifier.weight(1f)) {
                                if (off) return@Pill
                                busy = busy + id
                                io(scope, { Mochi.decideApproval(ctx, approve, true) }, { busy = busy - id }) {
                                    busy = busy - id; RingService.clearApprovalNotice(ctx); refresh()
                                }
                            }
                            Pill("Not now", false, Modifier.weight(1f)) {
                                if (off) return@Pill
                                busy = busy + id
                                io(scope, { Mochi.decideApproval(ctx, approve, false) }, { busy = busy - id }) {
                                    busy = busy - id; RingService.clearApprovalNotice(ctx); refresh()
                                }
                            }
                        }
                    }
                }
            }
            // tiles
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Tile(c.blueBg, Modifier.weight(1f).heightIn(min = 120.dp), onClick = {
                    MainActivity.openChat(ctx, latest?.optString("id"))
                }) {
                    LineIcon(Icons2.Chat, c.blueInk, 24.dp)
                    Text(latest?.optString("title")?.ifBlank { null } ?: "Chat", style = Type.body.copy(fontWeight = Type.strong),
                        color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(latest?.optString("lastMessageAt")?.let(::ago) ?: "Your latest chat", style = Type.small, color = c.muted)
                }
                Tile(c.greenBg, Modifier.weight(1f).heightIn(min = 120.dp), onClick = { go("inbox") }) {
                    LineIcon(Icons2.Clock, c.greenInk, 24.dp)
                    Text(notice?.optString("title")?.ifBlank { null } ?: "Nothing new", style = Type.body.copy(fontWeight = Type.strong),
                        color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(notice?.optString("body") ?: "Reminders and updates", style = Type.small, color = c.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Tile(c.violetBg, Modifier.weight(1f), onClick = { go("memory") }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        LineIcon(Icons2.Memory, c.violetInk, 22.dp)
                        Text("Memory", style = Type.body.copy(fontWeight = Type.strong), color = c.ink)
                    }
                }
                Tile(c.field, Modifier.weight(1f), onClick = { go("inbox") }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        LineIcon(Icons2.Bell, c.ink, 22.dp)
                        Text("Inbox", style = Type.body.copy(fontWeight = Type.strong), color = c.ink)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("All chats →", style = Type.small, color = c.muted,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { go("chats") }
                        .padding(vertical = Space.m, horizontal = Space.xs))
                Text("Settings", style = Type.small, color = c.muted,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { showSetup = !showSetup }
                        .padding(vertical = Space.m, horizontal = Space.xs))
                Text("Phone abilities", style = Type.small, color = c.muted,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { ctx.startActivity(Intent(ctx, AbilitiesActivity::class.java)) }
                        .padding(vertical = Space.m, horizontal = Space.xs))
            }
        }
        // the two things done most, in thumb reach
        Column(Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.xl, top = Space.m, bottom = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Field(ask, { ask = it }, "Ask Mochi anything", Modifier.weight(1f)) {
                    if (ask.isNotBlank()) RoundButton(Icons2.Send, "Send", c.ink, c.bg, 40.dp) {
                        MainActivity.openChat(ctx, new = true, text = ask, send = true); ask = ""
                    }
                }
                RoundButton(Icons2.Plus, "Attach photo or file", c.field, c.ink) { pick.launch("*/*") }
            }
            Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(c.coral)
                .clickable(role = Role.Button) { MainActivity.callMochi(ctx) }.semantics { contentDescription = "Call Mochi" },
                contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    LineIcon(Icons2.Mic, androidx.compose.ui.graphics.Color.White)
                    Text("Call Mochi", style = Type.title, color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }
}

@Composable
private fun SetupCard(onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    var host by remember { mutableStateOf(Mochi.host(ctx)) }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text("Connect to your server", style = Type.title, color = c.ink)
        Text("Over Tailscale only: keep the Tailscale app connected.", style = Type.small, color = c.muted)
        Field(host, { host = it }, "Server, e.g. xarvis-core.tailnet.ts.net", singleLine = true)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.field).padding(Space.l)) {
            if (token.isEmpty()) Text("Mochi's token", style = Type.body, color = c.muted)
            BasicTextField(token, { token = it }, singleLine = true, textStyle = Type.body.copy(color = c.ink),
                cursorBrush = SolidColor(c.ink), visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Mochi's token" })
        }
        if (error.isNotEmpty()) Text(error, style = Type.small, color = c.danger)
        Pill("Save", true, Modifier.fillMaxWidth()) {
            val h = host.trim().removePrefix("https://").substringBefore('/').substringBefore(':')
            val t = token.trim()
            when {
                h.isEmpty() || (t.isEmpty() && !Mochi.configured(ctx)) -> error = "Enter both the server and the token."
                h.contains(' ') || "https://$h/".toHttpUrlOrNull() == null ->
                    error = "That server address isn't valid (e.g. xarvis-core.tailnet.ts.net)."
                else -> {
                    Mochi.save(ctx, h, t.ifEmpty { Mochi.token(ctx) })
                    RingService.reconnect(ctx)
                    onSaved()
                }
            }
        }
        Text("Wake word, phone abilities and more are under Phone abilities.", style = Type.small, color = c.muted)
    }
}

/** A screen with a back button and a title. */
@Composable
private fun Screen(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null,
                   content: @Composable () -> Unit) {
    val c = LocalMochi.current
    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons2.Back, "Back", c.field, c.ink, 44.dp, onBack)
            Text(title, style = Type.title, color = c.ink, modifier = Modifier.weight(1f).padding(horizontal = Space.m))
            action?.invoke()
        }
        content()
    }
}

@Composable
private fun ChatsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<JSONObject>?>(null) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    fun load() = io(scope, { Mochi.threads(ctx) }, { error = "Can't reach Mochi. Is Tailscale on?" }) { all = it }
    LaunchedEffect(Unit) { load() }
    Screen("All chats", onBack, action = {
        RoundButton(Icons2.Plus, "New chat", c.ink, c.bg, 44.dp) { MainActivity.openChat(ctx, new = true) }
    }) {
        Field(query, { query = it }, "Search chats", Modifier.fillMaxWidth().padding(horizontal = Space.l), singleLine = true)
        if (error.isNotEmpty()) Text(error, style = Type.small, color = c.danger, modifier = Modifier.padding(Space.l))
        val shown = all.orEmpty().filter { query.isBlank() || it.optString("title").contains(query, ignoreCase = true) }
        LazyColumn(contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (all == null && error.isEmpty()) item { Text("Loading…", style = Type.body, color = c.muted) }
            if (all != null && shown.isEmpty()) item {
                Text(if (query.isBlank()) "No chats yet." else "No chat matches \"$query\".", style = Type.body, color = c.muted)
            }
            items(shown) { t ->
                val current = t.optString("id") == Mochi.thread(ctx)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (current) c.blueBg else c.card)
                    .clickable { MainActivity.openChat(ctx, t.optString("id")) }.padding(Space.l),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(t.optString("title").ifBlank { "Conversation" }, style = Type.body.copy(fontWeight = Type.strong),
                            color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(ago(t.optString("lastMessageAt")) + if (current) " · current" else "", style = Type.small, color = c.muted)
                    }
                    RoundButton(Icons2.Trash, "Delete chat", androidx.compose.ui.graphics.Color.Transparent, c.muted, 44.dp) {
                        deleting = t
                    }
                }
            }
        }
    }
    deleting?.let { t ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text("Delete this chat?") },
            text = { Text("\"${t.optString("title").ifBlank { "Conversation" }}\" and its messages are removed. Memory keeps what Mochi learned.") },
            confirmButton = { TextButton({
                deleting = null
                io(scope, { Mochi.deleteThread(ctx, t.optString("id")) }, { error = "Couldn't delete: ${it.message}" }) { load() }
            }) { Text("Delete", color = c.danger) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Keep") } })
    }
}

@Composable
private fun MemoryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<JSONObject>?>(null) }
    var error by remember { mutableStateOf("") }
    var teach by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<JSONObject?>(null) }
    fun load() = io(scope, { Mochi.memories(ctx, query) }, { error = "Can't reach Mochi's memory. (${it.message})" }) {
        items = it; error = ""
    }
    LaunchedEffect(query) { delay(if (query.isEmpty()) 0 else 400); load() }
    Screen("Memory", onBack, action = {
        RoundButton(Icons2.Plus, "Teach Mochi something", c.ink, c.bg, 44.dp) { teach = true }
    }) {
        Field(query, { query = it }, "Search what Mochi remembers", Modifier.fillMaxWidth().padding(horizontal = Space.l),
            singleLine = true)
        Text(if (query.isBlank()) "Newest first. Wrong or outdated? Forget it, so Mochi won't repeat it."
            else "Best matches", style = Type.small, color = c.muted, modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.s))
        if (error.isNotEmpty()) Text(error, style = Type.small, color = c.danger, modifier = Modifier.padding(horizontal = Space.l))
        LazyColumn(contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (items == null && error.isEmpty()) item { Text("Loading…", style = Type.body, color = c.muted) }
            if (items?.isEmpty() == true) item { Text("Nothing found.", style = Type.body, color = c.muted) }
            items(items.orEmpty()) { m ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).padding(Space.l),
                    verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text(m.optString("kind").replace('_', ' ').ifBlank { "memory" }, style = Type.small, color = c.violetInk)
                        Text(m.optString("text"), style = Type.body, color = c.ink, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                    RoundButton(Icons2.Trash, "Forget this", androidx.compose.ui.graphics.Color.Transparent, c.muted, 44.dp) {
                        forgetting = m
                    }
                }
            }
        }
    }
    forgetting?.let { m ->
        AlertDialog(onDismissRequest = { forgetting = null },
            title = { Text("Forget this?") },
            text = { Text(m.optString("text").take(240)) },
            confirmButton = { TextButton({
                forgetting = null
                io(scope, { Mochi.forget(ctx, m.optString("id")) }, { error = "Couldn't forget: ${it.message}" }) { load() }
            }) { Text("Forget", color = c.danger) } },
            dismissButton = { TextButton({ forgetting = null }) { Text("Keep") } })
    }
    if (teach) {
        var text by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { teach = false },
            title = { Text("Teach Mochi") },
            text = { Field(text, { text = it }, "e.g. My sister's birthday is 3 March") },
            confirmButton = { TextButton({
                teach = false
                if (text.isNotBlank()) io(scope, { Mochi.learn(ctx, text.trim()) }, { error = "Couldn't save: ${it.message}" }) { load() }
            }) { Text("Save") } },
            dismissButton = { TextButton({ teach = false }) { Text("Cancel") } })
    }
}

@Composable
private fun InboxScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    var notices by remember { mutableStateOf(Mochi.inbox(ctx)) }
    Screen("Inbox", onBack, action = {
        if (notices.isNotEmpty()) Text("Clear", style = Type.small, color = c.muted,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                Mochi.clearInbox(ctx); notices = emptyList()
            }.padding(Space.m))
    }) {
        LazyColumn(contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (notices.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = Space.xxl), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Mascot(Modifier.size(80.dp))
                    Text("All caught up", style = Type.title, color = c.ink)
                    Text("Reminders and updates from Mochi land here.", style = Type.small, color = c.muted)
                }
            }
            items(notices) { n ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card)
                    .clickable { MainActivity.openChat(ctx) }.padding(Space.l),
                    verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Row {
                        Text(n.optString("title").ifBlank { "Mochi" }, style = Type.body.copy(fontWeight = Type.strong),
                            color = c.ink, modifier = Modifier.weight(1f))
                        Text(DateUtils.getRelativeTimeSpanString(n.optLong("at")).toString(), style = Type.small, color = c.muted)
                    }
                    if (n.optString("body").isNotBlank()) Text(n.optString("body"), style = Type.body, color = c.ink)
                }
            }
            item { Spacer(Modifier.height(Space.xl)) }
        }
    }
}
