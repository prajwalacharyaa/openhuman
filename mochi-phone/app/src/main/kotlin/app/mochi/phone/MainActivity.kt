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
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import app.mochi.phone.ui.SwipeRow
import kotlinx.coroutines.launch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import app.mochi.phone.ui.Field
import app.mochi.phone.ui.Icons2
import app.mochi.phone.ui.Label
import app.mochi.phone.ui.LineIcon
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.Mascot
import app.mochi.phone.ui.MochiTheme
import app.mochi.phone.ui.Mood
import app.mochi.phone.ui.Pill
import app.mochi.phone.ui.Refreshable
import app.mochi.phone.ui.RoundButton
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Tile
import app.mochi.phone.ui.Type
import app.mochi.phone.ui.io
import app.mochi.phone.ui.WhileVisible
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.time.Instant

/**
 * Home: everything one tap away. Decisions to answer right on their card, an "Ask Mochi anything" bar (a new chat
 * with no extra screen), tiles for the latest chat, Mochi's agents, Memory and Inbox, and Call Mochi at the
 * bottom. Also the first-time setup (server + Mochi's token) and the permissions a ringing phone needs.
 */
class MainActivity : ComponentActivity() {
    /** Which screen Home shows; a widget or notification tap switches it (Home is one instance, never stacked). */
    private val tab = mutableStateOf("home")

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_CALL) callMochi(this)
        tab.value = intent.getStringExtra(EXTRA_TAB) ?: "home"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO), 1)
        }
        if (savedInstanceState == null && intent.action == ACTION_CALL) callMochi(this)
        if (savedInstanceState == null) tab.value = intent.getStringExtra(EXTRA_TAB) ?: "home"
        setContent { MochiTheme { App(tab.value) { tab.value = it } } }
    }

    override fun onResume() {
        super.onResume()
        RingService.start(this)
        Mochi.forgetWakeWord(this) // the old Picovoice wake word is gone: its saved key and keyword file go too
    }

    companion object {
        const val ACTION_CALL = "app.mochi.phone.CALL"
        const val EXTRA_TAB = "tab"

        fun callMochi(c: Context) {
            if (Call.active) { // already on a call: back to it, never a second one
                c.startActivity(Intent(c, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
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
private fun App(tab: String, setTab: (String) -> Unit) {
    BackHandler(enabled = tab != "home") { setTab("home") }
    when (tab) {
        "chats" -> ChatsScreen { setTab("home") }
        "memory" -> MemoryScreen { setTab("home") }
        "inbox" -> InboxScreen { setTab("home") }
        "reminders" -> RemindersScreen { setTab("home") }
        "maps" -> MapsScreen { setTab("home") }
        "settings" -> SettingsScreen { setTab("home") }
        else -> HomeScreen { setTab(it) }
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

internal fun ago(iso: String): String = runCatching {
    DateUtils.getRelativeTimeSpanString(Instant.parse(iso).toEpochMilli()).toString()
}.getOrDefault("")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(go: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var configured by remember { mutableStateOf(Mochi.configured(ctx)) }
    var approvals by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var latest by remember { mutableStateOf<JSONObject?>(null) }
    var notice by remember { mutableStateOf(Mochi.inbox(ctx).firstOrNull()) }
    var unread by remember { mutableIntStateOf(Mochi.unreadInbox(ctx)) }
    var ram by remember { mutableStateOf("") }
    var showLogins by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf("") }
    var fixList by remember { mutableStateOf(fixes(ctx)) }
    var busy by remember { mutableStateOf(setOf<String>()) }
    var update by remember { mutableStateOf<JSONObject?>(null) }
    var updating by remember { mutableStateOf(-1f) } // download progress, -1 = not downloading
    var updateError by remember { mutableStateOf("") }
    var onCall by remember { mutableStateOf(Call.active) }
    var muted by remember { mutableStateOf(Call.muted) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var tokens by remember { mutableStateOf<Map<String, Doing.Tokens>>(emptyMap()) }
    var team by remember { mutableStateOf(Agents.cached(ctx)) }
    var hiringWaiting by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { io(scope, { Doing.backfill(ctx) }) {} }
    WhileVisible {
        while (true) { onCall = Call.active; muted = Call.muted || Call.frozen; now = System.currentTimeMillis(); delay(1_000) }
    }
    var updateCheckedAt by remember { mutableLongStateOf(0L) }

    fun refresh() {
        if (!Mochi.configured(ctx)) return
        io(scope, { Mochi.approvals(ctx) }) { approvals = it }
        io(scope, { Mochi.threads(ctx).firstOrNull() }, { if (latest == null) latest = Mochi.cachedThreads(ctx).firstOrNull() }) { latest = it }
        io(scope, { Mochi.ramLabel(ctx) }) { ram = it }
        io(scope, { Doing.today(ctx) }) { tokens = it }
        io(scope, { Agents.load(ctx) }) { team = it }
        io(scope, { Agents.board(ctx).first.count { it.optString("status") == "waiting" } }) { hiringWaiting = it }
        notice = Mochi.inbox(ctx).firstOrNull()
        unread = Mochi.unreadInbox(ctx)
        fixList = fixes(ctx)
        // a new app version is rare: look every 30 min, not on every refresh
        if (updating < 0 && System.currentTimeMillis() - updateCheckedAt > 30 * 60_000L) {
            updateCheckedAt = System.currentTimeMillis()
            io(scope, { Updater.available(ctx) }) { update = it }
        }
    }
    WhileVisible(configured) {
        while (true) { refresh(); delay(10_000) }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) ctx.startActivity(Intent(ctx, ChatActivity::class.java).setAction(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_STREAM, u).putExtra(ChatActivity.EXTRA_TEXT, ask))
    }

    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding().imePadding()) {
        Refreshable({ refresh() }, Modifier.weight(1f)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m)) {
            // greeting, connection, Mochi
            Row(Modifier.fillMaxWidth().padding(top = Space.xl), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    val stranger = Mochi.lastLogins.any { !it.optBoolean("yours") }
                    Text(when {
                        !configured -> "Set up Mochi to start"
                        RingService.connected -> listOf("Connected", ram).filter { it.isNotEmpty() }.joinToString(" · ")
                        else -> "Not connected. Is Tailscale on?"
                    }, style = Type.small, color = if (stranger && RingService.connected) c.danger else c.muted,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { showLogins = !showLogins })
                    // tap the line: who is logged in to the server over SSH, from which device, since when
                    if (showLogins && RingService.connected) {
                        if (Mochi.lastLogins.isEmpty()) Text("Nobody is logged in over SSH.", style = Type.small, color = c.muted)
                        Mochi.lastLogins.forEach { l ->
                            val since = l.optString("since").split(" ").getOrNull(2)?.take(5) ?: ""
                            Text("${l.optString("user")} from ${l.optString("from")}${if (since.isNotEmpty()) " since $since" else ""}" +
                                if (l.optBoolean("yours")) "" else "  (NOT one of your devices)",
                                style = Type.small, color = if (l.optBoolean("yours")) c.muted else c.danger)
                        }
                    }
                    Text(greeting(), style = Type.display, color = c.ink)
                }
                // Mochi, with today's tokens per model under it (Gemini is estimated from the call audio)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Mascot(Modifier.size(72.dp), onTap = {})
                    val gemini = Doing.geminiToday(ctx) + (if (onCall) Call.geminiTokens else 0)
                    val lines = tokens.entries.filter { it.value.total > 0 }.sortedByDescending { it.value.total }
                        .map { "${it.key.replaceFirstChar { ch -> ch.uppercase() }} ${Doing.fmt(it.value.total)}" } +
                        (if (gemini > 0) listOf("Gemini ≈${Doing.fmt(gemini)}") else emptyList())
                    if (lines.isNotEmpty()) Text(lines.joinToString("\n"), style = Type.small.copy(fontSize = 10.sp,
                        lineHeight = 13.sp), color = c.muted, textAlign = TextAlign.Center)
                }
            }
            if (!configured) SetupCard { configured = true }
            for (f in fixList) {
                Text(f.label, style = Type.small.copy(fontWeight = Type.strong), color = c.amberInk,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.amberBg)
                        .clickable(role = Role.Button) { ctx.startActivity(f.intent) }.padding(Space.l))
            }
            // Mochi at work: the latest step; a tap shows everything it's doing
            Doing.latestStep()?.takeIf { Doing.busy(now) }?.let { step ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.blueBg)
                    .clickable(role = Role.Button) { DoingActivity.open(ctx) }.padding(horizontal = Space.l, vertical = Space.m),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    Mascot(Modifier.size(32.dp), mood = Mood.Thinking)
                    Column(Modifier.weight(1f)) {
                        Text("Mochi is working", style = Type.small.copy(fontWeight = Type.strong), color = c.blueInk)
                        Text(step.text, style = Type.small, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("See all ›", style = Type.small, color = c.blueInk)
                }
            }
            // a new version of this app, from the server over Tailscale
            update?.let { u ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.blueBg).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Text("Update ready", style = Type.small.copy(fontWeight = Type.strong), color = c.blueInk)
                    Text("Mochi version ${u.optInt("version")} · ${u.optLong("size") / 1_000_000} MB", style = Type.title, color = c.ink)
                    if (updating >= 0) LinearProgressIndicator(progress = { updating }, modifier = Modifier.fillMaxWidth(),
                        color = c.blueInk, trackColor = c.bg)
                    if (updateError.isNotEmpty()) Text(updateError, style = Type.small, color = c.danger)
                    if (!Updater.allowed(ctx)) Text("First allow Mochi to install updates (once), then tap Update.",
                        style = Type.small, color = c.muted)
                    Pill(if (updating >= 0) "Downloading…" else "Update", true, Modifier.fillMaxWidth()) {
                        if (updating >= 0) return@Pill
                        if (!Updater.allowed(ctx)) { ctx.startActivity(Updater.allowIntent(ctx)); return@Pill }
                        updating = 0f; updateError = ""
                        io(scope, { Updater.install(ctx, u) { p -> scope.launch { updating = p } } },
                            { updating = -1f; updateError = "Update failed: ${Room.friendly(it)}" }) { updating = -1f }
                    }
                }
            }
            // decisions, answered right here
            for (a in approvals) {
                val id = a.optString("id")
                val approve = a.optString("approve_url")
                val isCall = approve.isEmpty()
                val replyThread = a.optString("thread_id")
                // a LinkedIn post is read in full (text and image) on its approval page before approving
                val isPost = a.optString("kind") == "linkedin"
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.amberBg)
                    .clickable(enabled = isPost || replyThread.isNotEmpty()) {
                        if (isPost) ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(approve)))
                        else MainActivity.openChat(ctx, replyThread)
                    }.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Text(if (isCall) (if (a.optString("result") == "declined") "Declined call" else "Missed call")
                        else "Waiting for you", style = Type.small.copy(fontWeight = Type.strong), color = c.amberInk)
                    Text(if (isCall) a.optString("question") else a.optString("title", "Approval"),
                        style = Type.title, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (!isCall && a.optString("text").isNotBlank()) Text(a.optString("text"), style = Type.small,
                        color = c.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (isPost) Text("Tap to read the whole post", style = Type.small, color = c.amberInk)
                    else if (replyThread.isNotEmpty()) Text("Tap to answer by typing", style = Type.small, color = c.amberInk)
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
                // Mochi's agents: who's working (their face moves), or the team resting; a tap opens Agents
                val busyAgent = team.firstOrNull { Agents.working(it.name, now) }
                val hr = team.firstOrNull { it.hr }
                Tile(c.greenBg, Modifier.weight(1f).heightIn(min = 120.dp), onClick = {
                    AgentsActivity.open(ctx, if (hiringWaiting > 0) hr?.name else busyAgent?.name)
                }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        (busyAgent?.let { listOf(it) } ?: team.take(3)).forEach { a ->
                            Mascot(Modifier.size(30.dp), mood = if (busyAgent != null) Mood.Thinking else Mood.Sleepy,
                                look = a.look, label = a.display)
                        }
                        if (team.isEmpty()) LineIcon(Icons2.Chat, c.greenInk, 24.dp)
                    }
                    Text(when {
                        hiringWaiting > 0 -> "${hr?.display ?: "SY"}: $hiringWaiting to approve"
                        busyAgent != null -> "${busyAgent.display} is working"
                        else -> "Agents"
                    }, style = Type.body.copy(fontWeight = Type.strong), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(busyAgent?.let { Agents.latest(it.name)?.text } ?: team.joinToString(" · ") { it.display }
                        .ifBlank { "Mochi's team" }, style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                for ((key, label, bg) in listOf(Triple("memory", "Memory", c.violetBg), Triple("inbox", "Inbox", c.field),
                        Triple("maps", "Maps", c.blueBg))) {
                    Tile(bg, Modifier.weight(1f), onClick = { go(key) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LineIcon(when (key) { "memory" -> Icons2.Memory; "inbox" -> Icons2.Bell; else -> Icons2.Map },
                                when (key) { "memory" -> c.violetInk; "maps" -> c.blueInk; else -> c.ink }, 22.dp)
                            val count = if (key == "inbox") unread else 0
                            if (count > 0) Text(if (count > 9) "9+" else "$count", style = Type.small.copy(fontWeight = Type.strong),
                                color = Color.White, modifier = Modifier.padding(start = Space.s).clip(RoundedCornerShape(10.dp))
                                    .background(c.danger).padding(horizontal = 6.dp, vertical = 1.dp))
                        }
                        Text(label, style = Type.body.copy(fontWeight = Type.strong), color = c.ink)
                    }
                }
            }
            // six links: they wrap to a second line on a narrow phone instead of being cut off
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("All chats →", style = Type.small, color = c.muted,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { go("chats") }
                        .heightIn(min = 44.dp).padding(vertical = Space.m, horizontal = Space.xs))
                Text("Settings", style = Type.small, color = c.muted,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { go("settings") }
                        .heightIn(min = 44.dp).padding(vertical = Space.m, horizontal = Space.xs))
                Text("Reminders", style = Type.small, color = c.muted,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { go("reminders") }
                        .heightIn(min = 44.dp).padding(vertical = Space.m, horizontal = Space.xs))
                Text("Books", style = Type.small, color = c.muted,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { ctx.startActivity(Intent(ctx, BooksActivity::class.java)) }
                        .heightIn(min = 44.dp).padding(vertical = Space.m, horizontal = Space.xs))
                Text("Room", style = Type.small, color = c.muted,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { ctx.startActivity(Intent(ctx, RoomActivity::class.java)) }
                        .heightIn(min = 44.dp).padding(vertical = Space.m, horizontal = Space.xs))
            }
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
            if (onCall) {
                // a call is on: go back to it (not a second call), with mute and hang up right here
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Box(Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF2E9D5B))
                        .clickable(role = Role.Button) { ctx.startActivity(Intent(ctx, CallActivity::class.java)) }
                        .semantics { contentDescription = "Back to the call" }, contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            LineIcon(Icons2.Phone, Color.White)
                            Text("Back to call", style = Type.title, color = Color.White)
                        }
                    }
                    RoundButton(if (muted) Icons2.MicOff else Icons2.Mic, if (muted) "Unmute" else "Mute",
                        if (muted) c.ink else c.field, if (muted) c.bg else c.ink, 56.dp) {
                        if (Call.frozen) Call.unfreeze() else Call.muted = !Call.muted
                        muted = Call.muted || Call.frozen
                    }
                    RoundButton(Icons2.HangUp, "Hang up", c.danger, Color.White, 56.dp) { Call.end(); onCall = false }
                }
            } else {
                Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).background(c.coral)
                    .clickable(role = Role.Button) { MainActivity.callMochi(ctx) }.semantics { contentDescription = "Call Mochi" },
                    contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        LineIcon(Icons2.Mic, Color.White)
                        Text("Call Mochi", style = Type.title, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SetupCard(framed: Boolean = true, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    var host by remember { mutableStateOf(Mochi.host(ctx)) }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Column(if (framed) Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).padding(20.dp)
        else Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        if (framed) Text("Connect to your server", style = Type.title, color = c.ink)
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
        if (framed) Text("Phone abilities (texts, notifications, assistant) are under Phone abilities.", style = Type.small, color = c.muted)
    }
}

/** A screen with a back button and a title. */
@Composable
internal fun Screen(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null,
                   content: @Composable () -> Unit) {
    val c = LocalMochi.current
    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons2.Back, "Back", c.field, c.ink, 44.dp, onBack)
            Text(title, style = Type.title, color = c.ink,
                modifier = Modifier.weight(1f).padding(horizontal = Space.m).semantics { heading() })
            action?.invoke()
        }
        content()
    }
}

/** Files December attached in chats: newest first, the chat they came from; tap opens it, swipe deletes the file. */
