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
        "maps" -> MapsScreen { setTab("home") }
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
    var unread by remember { mutableIntStateOf(Mochi.unreadInbox(ctx)) }
    var ram by remember { mutableStateOf("") }
    var showLogins by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf("") }
    var fixList by remember { mutableStateOf(fixes(ctx)) }
    var showSetup by remember { mutableStateOf(false) }
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
        io(scope, { Mochi.threads(ctx).firstOrNull() }) { latest = it }
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
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.xl),
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
            if (!configured || showSetup) SetupCard { configured = true; showSetup = false }
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
                            { updating = -1f; updateError = "Update failed: ${it.message}" }) { updating = -1f }
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
            if (onCall) {
                // a call is on: go back to it (not a second call), with mute and hang up right here
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Box(Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF2E9D5B))
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
                Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(c.coral)
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
        Text("Phone abilities (texts, notifications, assistant) are under Phone abilities.", style = Type.small, color = c.muted)
        if (Mochi.configured(ctx)) UnlockCard()
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

/** Files December attached in chats: newest first, the chat they came from; tap opens it, swipe deletes the file. */
@Composable
private fun UploadsPage(snack: SnackbarHostState) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<JSONObject>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        io(scope, { Mochi.uploads(ctx) }, { loading = false; error = "Can't reach Mochi. (${it.message})" }) {
            loading = false; items.clear(); items.addAll(it)
        }
    }
    fun remove(l: List<JSONObject>) = deleteWithUndo(scope, snack, if (l.size == 1) "File deleted" else "${l.size} files deleted", l,
        hide = { x -> items.removeAll { m -> x.any { it.optString("id") == m.optString("id") } } },
        restore = { items.addAll(0, it) },
        delete = { x -> Mochi.deleteUploads(ctx, x.map { it.optString("id") }) },
        failed = { error = "Couldn't delete: ${it.message}" })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (error.isNotEmpty()) item { Text(error, style = Type.small, color = c.danger) }
        if (loading) item { Text("Loading…", style = Type.small, color = c.muted) }
        else if (items.isEmpty()) item { Text("No uploads.", style = Type.body, color = c.muted) }
        items(items, key = { it.optString("id") }) { u ->
            SwipeRow(false, false, "Delete", onDelete = { remove(listOf(u)) }, onToggle = {},
                onOpen = { MainActivity.openChat(ctx, u.optString("chat")) }) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(u.optString("name"), style = Type.body.copy(fontWeight = Type.strong), color = c.ink, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    val kb = u.optLong("size") / 1024
                    Text(listOf(if (kb >= 1024) "%.1f MB".format(kb / 1024.0) else "$kb KB",
                        DateUtils.getRelativeTimeSpanString(u.optLong("at") * 1000).toString(),
                        u.optString("title").ifBlank { "a chat" }).joinToString(" · "), style = Type.small, color = c.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Removes [items] from view now and deletes them after a few seconds unless Undo is tapped. */
private fun <T> deleteWithUndo(scope: kotlinx.coroutines.CoroutineScope, snack: SnackbarHostState, label: String,
                               items: List<T>, hide: (List<T>) -> Unit, restore: (List<T>) -> Unit,
                               delete: (List<T>) -> Unit, failed: (Throwable) -> Unit) {
    hide(items)
    scope.launch {
        snack.currentSnackbarData?.dismiss()
        var undo: Boolean? = null
        try {
            undo = snack.showSnackbar(label, actionLabel = "Undo", duration = SnackbarDuration.Short) ==
                SnackbarResult.ActionPerformed
        } finally {
            // leaving the tab or screen before the snackbar ends cancels the wait: delete anyway (it was not undone)
            if (undo == null) Thread { runCatching { delete(items) } }.start()
        }
        if (undo == true) restore(items) else io(scope, { delete(items) }, { restore(items); failed(it) }) {}
    }
}

/** The bar shown while selecting: how many, select all, delete, cancel. */
@Composable
private fun SelectionBar(count: Int, verb: String, onAll: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    val c = LocalMochi.current
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        RoundButton(Icons2.Close, "Stop selecting", c.field, c.ink, 44.dp, onCancel)
        Text("$count selected", style = Type.title, color = c.ink, modifier = Modifier.weight(1f))
        Text("All", style = Type.small, color = c.blueInk, modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onAll).padding(Space.m))
        Pill("$verb $count", true, height = 40.dp) { if (count > 0) onDelete() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsScreen(onBack: () -> Unit) {
    val teamCtx = LocalContext.current
    val team = remember { Agents.cached(teamCtx) }
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val all = remember { mutableStateListOf<JSONObject>() }
    var loaded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    fun load() = io(scope, { Mochi.threads(ctx) }, { error = "Can't reach Mochi. Is Tailscale on?" }) {
        all.clear(); all.addAll(it); loaded = true
    }
    fun remove(list: List<JSONObject>) = deleteWithUndo(scope, snack,
        if (list.size == 1) "Chat deleted" else "${list.size} chats deleted", list,
        hide = { l -> all.removeAll { t -> l.any { it.optString("id") == t.optString("id") } } },
        restore = { all.addAll(it); all.sortByDescending { t -> t.optString("lastMessageAt") } },
        delete = { l -> l.forEach { Mochi.deleteThread(ctx, it.optString("id")) } },
        failed = { error = "Couldn't delete: ${it.message}" })
    LaunchedEffect(Unit) { load() }
    BackHandler(enabled = selecting) { selected = emptySet() }
    val shown = all.filter { query.isBlank() || it.optString("title").contains(query, ignoreCase = true) }
    Box(Modifier.fillMaxSize()) {
        Screen("All chats", onBack, action = {
            RoundButton(Icons2.Plus, "New chat", c.ink, c.bg, 44.dp) { MainActivity.openChat(ctx, new = true) }
        }) {
            if (selecting) SelectionBar(selected.size, "Delete", { selected = shown.map { it.optString("id") }.toSet() },
                { confirm = true }, { selected = emptySet() })
            else Field(query, { query = it }, "Search chats", Modifier.fillMaxWidth().padding(horizontal = Space.l), singleLine = true)
            if (error.isNotEmpty()) Text(error, style = Type.small, color = c.danger, modifier = Modifier.padding(Space.l))
            LazyColumn(contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                if (!loaded && error.isEmpty()) item { Text("Loading…", style = Type.body, color = c.muted) }
                if (loaded && shown.isEmpty()) item {
                    Text(if (query.isBlank()) "No chats yet." else "No chat matches \"$query\".", style = Type.body, color = c.muted)
                }
                if (loaded && shown.isNotEmpty() && !selecting) item {
                    Text("Swipe a chat to delete it, or hold one to pick several.", style = Type.small, color = c.muted)
                }
                items(shown, key = { it.optString("id") }) { t ->
                    val id = t.optString("id")
                    val current = id == Mochi.thread(ctx)
                    SwipeRow(selecting, id in selected, "Delete", onDelete = { remove(listOf(t)) },
                        onToggle = { selected = if (id in selected) selected - id else selected + id },
                        onOpen = { MainActivity.openChat(ctx, id) }) {
                        // a chat with one of Mochi's agents shows its face and name
                        val owner = Mochi.agentOf(t)
                        val agentInfo = owner?.let { o -> team.firstOrNull { it.name == o } }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                            if (owner != null) Mascot(Modifier.size(34.dp), mood = Mood.Idle, look = agentInfo?.look,
                                label = Agents.display(owner, team))
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(t.optString("title").ifBlank { "Conversation" }, style = Type.body.copy(fontWeight = Type.strong),
                                    color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text((owner?.let { Agents.display(it, team) + " · " } ?: "") + ago(t.optString("lastMessageAt")) +
                                    if (current) " · current" else "", style = Type.small, color = c.muted)
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).systemBarsPadding())
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Delete ${selected.size} chats?") },
        text = { Text("Their messages are removed. Memory keeps what Mochi learned from them.") },
        confirmButton = { TextButton({
            confirm = false
            val list = all.filter { it.optString("id") in selected }
            selected = emptySet(); remove(list)
        }) { Text("Delete", color = c.danger) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Keep") } })
}

@Composable
private fun MemoryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    // three pages you can swipe between: what Mochi knows, the conversation log, and uploaded files
    val pager = androidx.compose.foundation.pager.rememberPagerState { 3 }
    val tab = if (pager.currentPage == 1) "conversation" else "learning"
    var query by remember { mutableStateOf("") }
    val items = remember { mutableStateListOf<JSONObject>() }
    var cursor by remember { mutableStateOf<String?>(null) }
    var end by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var teach by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) } // a new search/tab drops pages of the old one
    val selecting = selected.isNotEmpty()
    val list = rememberLazyListState()

    fun more() {
        if (loading || end) return
        loading = true
        val gen = generation
        io(scope, { Mochi.memories(ctx, query, listOf(tab), cursor) },
            { loading = false; error = "Can't reach Mochi's memory. (${it.message})" }) { page ->
            if (gen != generation) return@io
            loading = false; error = ""
            val have = items.map { it.optString("id") }.toSet()
            items.addAll(page.items.filter { it.optString("id") !in have })
            cursor = page.next
            end = page.next == null || page.items.isEmpty()
        }
    }
    fun restart() { generation++; items.clear(); cursor = null; end = false; loading = false; selected = emptySet(); more() }
    fun remove(l: List<JSONObject>) = deleteWithUndo(scope, snack,
        if (l.size == 1) "Forgotten" else "${l.size} forgotten", l,
        hide = { x -> items.removeAll { m -> x.any { it.optString("id") == m.optString("id") } } },
        restore = { items.addAll(0, it) },
        delete = { x -> Mochi.forget(ctx, x.map { it.optString("id") }) },
        failed = { error = "Couldn't forget: ${it.message}" })

    LaunchedEffect(query, tab) { delay(if (query.isEmpty()) 0 else 400); restart() }
    // endless scroll: near the bottom, fetch the next page
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= items.size - 8) more()
        }
    }
    BackHandler(enabled = selecting) { selected = emptySet() }

    Box(Modifier.fillMaxSize()) {
        Screen("Memory", onBack, action = {
            RoundButton(Icons2.Plus, "Teach Mochi something", c.ink, c.bg, 44.dp) { teach = true }
        }) {
            if (selecting) SelectionBar(selected.size, "Forget", { selected = items.map { it.optString("id") }.toSet() },
                { confirm = true }, { selected = emptySet() })
            else Column(Modifier.padding(horizontal = Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    listOf("What Mochi knows", "Conversations", "Uploads").forEachIndexed { i, label ->
                        val on = pager.currentPage == i
                        Box(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(if (on) c.ink else c.field)
                            .clickable(role = Role.Tab) { scope.launch { pager.animateScrollToPage(i) } }.padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center) {
                            Text(label, style = Type.small.copy(fontWeight = if (on) Type.strong else null),
                                color = if (on) c.bg else c.ink, maxLines = 1)
                        }
                    }
                }
                if (pager.currentPage < 2) Field(query, { query = it }, "Search what Mochi remembers", Modifier.fillMaxWidth(), singleLine = true)
            }
            if (error.isNotEmpty()) Text(error, style = Type.small, color = c.danger, modifier = Modifier.padding(horizontal = Space.l))
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) { page ->
            if (page == 2) { UploadsPage(snack); return@HorizontalPager }
            if (page != pager.currentPage) { Box(Modifier.fillMaxSize()); return@HorizontalPager }
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                if (items.isEmpty() && !loading && end) item { Text("Nothing here.", style = Type.body, color = c.muted) }
                items(items, key = { it.optString("id") }) { m ->
                    val id = m.optString("id")
                    SwipeRow(selecting, id in selected, "Forget", onDelete = { remove(listOf(m)) },
                        onToggle = { selected = if (id in selected) selected - id else selected + id },
                        onOpen = { selected = selected + id }) {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            val at = m.optJSONObject("meta")?.optString("observed_at").orEmpty()
                            Text(listOf(m.optString("kind").ifBlank { "memory" }, ago(at)).filter { it.isNotBlank() }
                                .joinToString(" · "), style = Type.small, color = c.violetInk)
                            Text(m.optString("text"), style = Type.body, color = c.ink, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (loading) item { Text("Loading…", style = Type.small, color = c.muted) }
            }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).systemBarsPadding())
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Forget ${selected.size} items?") },
        text = { Text("Mochi won't find or repeat them again.") },
        confirmButton = { TextButton({
            confirm = false
            val l = items.filter { it.optString("id") in selected }
            selected = emptySet(); remove(l)
        }) { Text("Forget", color = c.danger) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Keep") } })
    if (teach) {
        var text by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { teach = false },
            title = { Text("Teach Mochi") },
            text = { Field(text, { text = it }, "e.g. My sister's birthday is 3 March") },
            confirmButton = { TextButton({
                teach = false
                if (text.isNotBlank()) io(scope, { Mochi.learn(ctx, text.trim()) }, { error = "Couldn't save: ${it.message}" }) {
                    scope.launch { pager.animateScrollToPage(0) }; restart()
                }
            }) { Text("Save") } },
            dismissButton = { TextButton({ teach = false }) { Text("Cancel") } })
    }
}

@Composable
private fun MapsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<JSONObject>?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { io(scope, { Mochi.graphs(ctx) }, { error = "Can't reach Mochi. Is Tailscale on?" }) { projects = it } }
    Screen("Project maps", onBack) {
        Text("How each project's parts connect. Tap one to explore it.", style = Type.small, color = c.muted,
            modifier = Modifier.padding(horizontal = Space.xl))
        if (error.isNotEmpty()) Text(error, style = Type.small, color = c.danger, modifier = Modifier.padding(Space.l))
        LazyColumn(contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (projects == null && error.isEmpty()) item { Text("Loading…", style = Type.body, color = c.muted) }
            if (projects?.isEmpty() == true) item {
                Text("No maps yet: Mochi makes one when it first works on a project.", style = Type.body, color = c.muted)
            }
            items(projects.orEmpty()) { p ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card)
                    .clickable { MapActivity.open(ctx, p.optString("name")) }.padding(Space.l),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.violetBg), contentAlignment = Alignment.Center) {
                        LineIcon(Icons2.Map, c.violetInk, 22.dp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(p.optString("name"), style = Type.body.copy(fontWeight = Type.strong), color = c.ink)
                        Text("${p.optInt("nodes")} parts · ${p.optInt("edges")} links · updated " +
                            DateUtils.getRelativeTimeSpanString(p.optLong("updated") * 1000), style = Type.small, color = c.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    var notices by remember { mutableStateOf(Mochi.inbox(ctx)) }
    val team = remember { Agents.cached(ctx) }
    LaunchedEffect(Unit) { Mochi.markInboxSeen(ctx); MochiWidget.update(ctx, force = true) }
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
                // a tap shows the whole notice (an agent's report can be long); "Open" goes where it's about
                var open by remember { mutableStateOf(false) }
                val title = n.optString("title").ifBlank { "Mochi" }
                val agent = team.firstOrNull { title.startsWith(it.display + " ") || title.startsWith(it.display + ":") }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card)
                    .clickable(onClickLabel = if (open) "Show less" else "Show all") { open = !open }.padding(Space.l),
                    verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        if (agent != null) Mascot(Modifier.size(28.dp), look = agent.look, label = agent.display)
                        Text(title, style = Type.body.copy(fontWeight = Type.strong), color = c.ink, modifier = Modifier.weight(1f))
                        Text(DateUtils.getRelativeTimeSpanString(n.optLong("at")).toString(), style = Type.small, color = c.muted)
                    }
                    val body = n.optString("body")
                    if (body.isNotBlank()) Text(body, style = Type.body, color = c.ink, maxLines = if (open) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                        if (body.length > 220 || body.count { it == '\n' } > 3)
                            Text(if (open) "Show less" else "Show all", style = Type.small, color = c.blueInk)
                        Text(if (agent != null) "Open ${agent.display}" else "Open chat", style = Type.small, color = c.blueInk,
                            modifier = Modifier.clickable(role = Role.Button) {
                                if (agent != null) AgentsActivity.open(ctx, agent.name) else MainActivity.openChat(ctx)
                            })
                    }
                }
            }
            item { Spacer(Modifier.height(Space.xl)) }
        }
    }
}
