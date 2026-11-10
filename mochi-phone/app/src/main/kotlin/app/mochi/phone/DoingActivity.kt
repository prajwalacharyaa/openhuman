package app.mochi.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mochi.phone.ui.Icons2
import app.mochi.phone.ui.LineIcon
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.Mascot
import app.mochi.phone.ui.MochiColors
import app.mochi.phone.ui.MochiTheme
import app.mochi.phone.ui.Mood
import app.mochi.phone.ui.Pill
import app.mochi.phone.ui.RoundButton
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Type
import app.mochi.phone.ui.io
import app.mochi.phone.ui.WhileVisible
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * "What Mochi is doing": Mochi in the middle, its helpers around it lighting up while they work, the tokens of this
 * call (or of today) under Mochi, and the requests as cards: each step in plain words with what happened inside it.
 * Double-tap Mochi for more lines per step; tap a step for everything that happened in it. Stop cancels Claude's
 * work; the round button calls Mochi right here (and mutes once the call is on).
 */
class DoingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MochiTheme { DoingScreen { finish() } } }
    }

    companion object {
        /** Opens the page, or brings it back to the front if it's already open (never a second copy). */
        fun open(c: Context) = c.startActivity(Intent(c, DoingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
}

/** One helper around Mochi. */
private class Lane(val key: String, val label: String, val bg: Color, val ink: Color)

private fun lanes(c: MochiColors, helpers: List<String>, team: List<Agents.Agent>, live: Boolean) = listOf(
    Lane("voice", "Voice", c.amberBg, c.amberInk),
    Lane("brain", "Claude", c.blueBg, c.blueInk),
    Lane("memory", "Memory", c.violetBg, c.violetInk),
    Lane("web", "Web", c.greenBg, c.greenInk),
    Lane("server", "Server", c.field, c.ink),
    Lane("phone", "Phone", c.amberBg, c.amberInk),
    Lane("github", "GitHub", c.field, c.ink),
) + (if (live) listOf(Lane("live", "Live", c.greenBg, c.greenInk)) else emptyList()) + helpers.take(3).map { who ->
    // a specialist (Mark, Melissa…) in its own colour; other helpers (builder, Explore…) in blue
    val agent = team.firstOrNull { it.who == who }
    if (agent != null) Lane("helper:$who", agent.display, agent.color.copy(alpha = 0.22f), agent.color)
    else Lane("helper:$who", who.removePrefix("Helper: ").take(14), c.blueBg, c.blueInk)
}

private fun laneOf(e: Doing.Event) = if (e.lane == "helper") "helper:" + e.who else e.lane

/** A step belongs to a bubble by its lane, and to an agent's bubble by who did it. */
private fun inLane(e: Doing.Event, key: String) = laneOf(e) == key || key == "helper:" + e.who

private fun laneInk(c: MochiColors, lane: String) = when (lane) {
    "voice", "phone" -> c.amberInk
    "brain", "helper" -> c.blueInk
    "memory" -> c.violetInk
    "web" -> c.greenInk
    else -> c.muted
}

/** The server's card for a call itself (mochi-activity), not shown here. */
private const val CALL_TASK = "Call with Mochi"

/** What Mochi says while a call is starting. */
private val CALL_HINTS = listOf("Calling Mochi…", "Connecting over Tailscale…", "Waking Gemini up…", "Say hi when Mochi answers")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DoingScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var filter by remember { mutableStateOf<String?>(null) }
    var detailed by remember { mutableStateOf(Mochi.detailed(ctx)) }
    var today by remember { mutableStateOf<Map<String, Doing.Tokens>>(emptyMap()) }
    var opened by remember { mutableStateOf<Doing.StepView?>(null) }
    var callTapAt by remember { mutableLongStateOf(0L) }
    var liveLink by remember { mutableStateOf<String?>(null) }   // a preview or live build to show December
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { io(scope, { Doing.backfill(ctx) }) {} }
    WhileVisible {
        var tick = 0
        while (true) {
            if (tick % 15 == 0) io(scope, { Doing.today(ctx) }) { today = it }
            if (tick % 15 == 0) io(scope, { Mochi.liveLink(ctx) }, { liveLink = null }) { liveLink = it }
            tick++
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val startCall = {
        callTapAt = System.currentTimeMillis()
        ctx.startForegroundService(Intent(ctx, CallService::class.java))
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCall() else ctx.startActivity(Intent(ctx, CallActivity::class.java).putExtra(CallActivity.EXTRA_ANSWER, true))
    }

    val tasks = Doing.tasks()
    val active = Doing.activeLanes(now) + (if (Call.active) setOf("voice") else emptySet())
    val busy = Doing.busy(now)
    val team = remember { Agents.cached(ctx) }
    val helpers = Doing.events.filter { (it.lane == "helper" || it.who.startsWith("Helper: ")) && now - it.at < 10 * 60_000 }
        .map { it.who }.filter { it.startsWith("Helper: ") }.distinct()
    val all = lanes(c, helpers, team, liveLink != null)
    val last = Doing.latestStep()
    val callSpeaking = Call.active && Call.said.lastOrNull()?.let { it.role != "user" && !it.final } == true
    // tapped and still coming up (gives up showing the spinner after 15 s if the call never started)
    val connecting = (Call.active && Call.status.startsWith("Connecting")) || (!Call.active && now - callTapAt < 3_000) ||
        (Call.active && Call.status.isBlank() && now - callTapAt < 15_000)
    val mood = when {
        connecting -> Mood.Surprised
        callSpeaking -> Mood.Speaking
        Call.active && Call.frozen -> Mood.Sleepy
        busy -> Mood.Thinking
        last == null || now - last.at > 5 * 60_000 -> Mood.Sleepy
        else -> Mood.Happy
    }
    // the tokens under Mochi: this call's, else today's
    val tokenLine = if (Call.active) {
        "This call · Claude ${Doing.fmt(Doing.claudeSince(Call.startedAtMs).total)} · ≈Gemini ${Doing.fmt(Call.geminiTokens)}"
    } else {
        val claude = today.values.fold(Doing.Tokens()) { a, t -> a + t }
        "Today · Claude ${Doing.fmt(claude.total)}" +
            today.entries.sortedByDescending { it.value.total }.joinToString("") { " · ${it.key.replaceFirstChar { ch -> ch.uppercase() }} ${Doing.fmt(it.value.total)}" } +
            " · ≈Gemini ${Doing.fmt(Doing.geminiToday(ctx))}"
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                RoundButton(Icons2.Back, "Back", c.field, c.ink, 44.dp, onBack)
                Column(Modifier.weight(1f).padding(horizontal = Space.xs)) {
                    Text("What Mochi is doing", style = Type.title, color = c.ink)
                    Text(when {
                        busy && last != null -> "Working: ${last.text}"
                        last != null -> "Resting · last step " + DateUtils.getRelativeTimeSpanString(last.at)
                        else -> "Nothing yet. Steps show up here as Mochi works."
                    }, style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // the chat of the call (or the current chat)
                RoundButton(Icons2.Chat, "Open the chat", c.field, c.ink, 44.dp) {
                    MainActivity.openChat(ctx, Call.thread ?: tasks.firstOrNull()?.task?.thread?.takeIf { it.startsWith("thread-") || it.startsWith("voice-") })
                }
                RoundButton(Icons2.Home, "Mochi home", c.field, c.ink, 44.dp) {
                    ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    onBack()
                }
            }
            // the call's own card ("Call with Mochi") is left out: it kept jumping to the top during a call
            // (the live call line above already says what the call is doing); requests asked on it still show
            val shown = tasks.filter { it.task?.text != CALL_TASK }.mapNotNull { t ->
                val steps = if (filter == null) t.steps else t.steps.filter { inLane(it.step, filter!!) }
                if (filter != null && steps.isEmpty()) null else t.copy(steps = steps)
            }
            // the page never moves on its own while December looks at Mochi or reads further down: new steps appear
            // inside their card and a new card appears in place. Only when the view sits exactly on the newest card
            // does a newer card (which would land just above the edge) take its place.
            val newest = shown.firstOrNull()?.let { it.task?.id ?: it.steps.firstOrNull()?.step?.id }
            LaunchedEffect(newest) {
                val firstCard = (if (Call.active) 2 else 1) + 1
                if (opened == null && !list.isScrollInProgress && list.firstVisibleItemIndex == firstCard &&
                    list.firstVisibleItemScrollOffset < 24) list.scrollToItem(firstCard)
            }
            LazyColumn(state = list, contentPadding = PaddingValues(start = Space.l, end = Space.l, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(Space.s)) {
                item("hero") {
                    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp)).background(c.card),
                        contentAlignment = Alignment.Center) {
                        val side = maxWidth
                        val ring = side * 0.36f
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // double-tap Mochi: more lines per step (remembered; the widget follows)
                            Box(Modifier.size(side * 0.3f).pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = {
                                    detailed = !detailed
                                    Mochi.setDetailed(ctx, detailed)
                                    MochiWidget.update(ctx, force = true)
                                })
                            }) { Mascot(Modifier.fillMaxSize(), mood = mood) }
                            Text(if (connecting) CALL_HINTS[((now / 2_000) % CALL_HINTS.size).toInt()] else tokenLine,
                                style = Type.small, color = if (connecting) c.amberInk else c.muted, textAlign = TextAlign.Center,
                                maxLines = 2, modifier = Modifier.widthIn(max = side * 0.5f))
                        }
                        all.forEachIndexed { i, lane ->
                            val a = -PI / 2 + 2 * PI * i / all.size
                            // double-tap an agent's bubble: that agent's page on the Agents screen
                            val agentName = lane.key.removePrefix("helper:").removePrefix("Helper: ")
                                .takeIf { lane.key.startsWith("helper:") }
                            LaneBubble(lane, lane.key in active, filter == lane.key,
                                Modifier.offset(x = ring * cos(a).toFloat(), y = ring * sin(a).toFloat()),
                                onDoubleClick = if (lane.key == "live") liveLink?.let { u -> {
                                    // double-tap Live: the preview / live build in the browser
                                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
                                    Unit
                                } } else agentName?.let { n -> { AgentsActivity.open(ctx, n) } }) {
                                filter = if (filter == lane.key) null else lane.key
                            }
                        }
                    }
                }
                if (Call.active) item("call") {
                    val said = Call.said.lastOrNull()
                    Text(when {
                        Call.frozen -> "On a call · frozen: Mochi hears nothing"
                        said != null && said.role != "user" && !said.final -> "On a call · Mochi is speaking"
                        said != null && said.role == "user" && !said.final -> "On a call · Mochi is listening to you"
                        else -> "On a call · " + Call.status.ifBlank { "listening" }
                    }, style = Type.small.copy(fontWeight = Type.strong), color = c.amberInk,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.amberBg)
                            .padding(horizontal = Space.m, vertical = Space.s))
                }
                item("bar") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((if (detailed) "Details on" else "Details off") + " (double-tap Mochi)" +
                            (filter?.let { f -> " · only ${all.firstOrNull { it.key == f }?.label}" } ?: ""),
                            style = Type.small, color = c.muted, modifier = Modifier.weight(1f).padding(vertical = Space.xs))
                        val stopThread = Doing.busyThread(now)
                        if (busy && stopThread != null) Pill("Stop Mochi", false, height = 36.dp) {
                            io(scope, { Mochi.cancel(ctx, stopThread) }) {}
                        }
                    }
                }
                if (shown.isEmpty()) item("empty") {
                    Text("Nothing here yet.", style = Type.body, color = c.muted, modifier = Modifier.padding(Space.l))
                }
                items(shown, key = { it.task?.id ?: it.steps.firstOrNull()?.step?.id ?: it.at.toString() }) { t ->
                    TaskCard(t, detailed) { opened = it }
                }
            }
        }

        // bottom right: call Mochi here (loading while it connects), then mute / unmute while the call is on
        val callOn = Call.active && !connecting
        Box(Modifier.align(Alignment.BottomEnd).systemBarsPadding().padding(Space.xl).size(64.dp).clip(CircleShape)
            .background(when { connecting -> c.amberInk; callOn && (Call.muted || Call.frozen) -> c.danger; callOn -> c.ink; else -> c.coral })
            .clickable(role = Role.Button) {
                when {
                    connecting -> Unit
                    callOn -> { if (Call.frozen) Call.unfreeze() else Call.muted = !Call.muted; now = System.currentTimeMillis() }
                    ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startCall()
                    else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            .semantics { contentDescription = when { connecting -> "Calling Mochi"; callOn && Call.muted -> "Unmute"; callOn -> "Mute"; else -> "Call Mochi" } },
            contentAlignment = Alignment.Center) {
            when {
                connecting -> CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
                callOn -> LineIcon(if (Call.muted || Call.frozen) Icons2.MicOff else Icons2.Mic, Color.White, 28.dp)
                else -> LineIcon(Icons2.Phone, Color.White, 28.dp)
            }
        }
    }

    opened?.let { s ->
        StepSheet(s, onClose = { opened = null })
    }
}

/** One request: what was asked, its models and tokens, and its steps (1–2 lines each, 3–4 with details on). */
@Composable
internal fun TaskCard(t: Doing.TaskView, detailed: Boolean, onOpen: (Doing.StepView) -> Unit) {
    val c = LocalMochi.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(t.task?.text ?: "Mochi at work", style = Type.body.copy(fontWeight = Type.strong), color = c.ink, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(DateUtils.getRelativeTimeSpanString(t.at).toString(), style = Type.small, color = c.muted)
        }
        if (t.models.isNotEmpty() || t.tokens.total > 0) Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            t.models.forEach { m -> Chip(m.replaceFirstChar { it.uppercase() }, c.blueBg, c.blueInk) }
            if (t.tokens.total > 0) Chip("${Doing.fmt(t.tokens.total)} tokens", c.field, c.muted)
        }
        // newest step at the top
        t.steps.asReversed().forEach { s -> StepRow(s, detailed) { onOpen(s) } }
    }
}

@Composable
private fun Chip(text: String, bg: Color, ink: Color) {
    Text(text, style = Type.small, color = ink, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(bg)
        .padding(horizontal = Space.s, vertical = 2.dp))
}

@Composable
private fun StepRow(s: Doing.StepView, detailed: Boolean, onClick: () -> Unit) {
    val c = LocalMochi.current
    val e = s.step
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick)
        .padding(vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Box(Modifier.padding(top = 7.dp).size(8.dp).clip(CircleShape).background(laneInk(c, e.lane)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.text, style = Type.body, color = c.ink, maxLines = if (detailed) 3 else 2, overflow = TextOverflow.Ellipsis)
            // normal: one line of what happened; details on: the specifics and the latest few lines
            val lines = s.lines.map { it.text }
            if (detailed) {
                if (e.detail.isNotBlank() && e.detail != e.text) Mono(e.detail, 2)
                lines.takeLast(3).asReversed().forEach { Text("› $it", style = Type.small, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            } else {
                (lines.lastOrNull() ?: e.detail.takeIf { it.isNotBlank() && it != e.text })?.let {
                    Text(it, style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (e.model.isNotEmpty()) Text(Doing.describe(e.model, e.tokens), style = Type.small, color = c.blueInk, maxLines = 1)
            if (s.lines.size > (if (detailed) 3 else 1)) Text("Tap for all ${s.lines.size} lines", style = Type.small, color = c.blueInk)
        }
        Text(DateUtils.formatDateTime(LocalContext.current, e.at, DateUtils.FORMAT_SHOW_TIME), style = Type.small, color = c.muted)
    }
}

@Composable
private fun Mono(text: String, maxLines: Int) {
    val c = LocalMochi.current
    Text(text, style = Type.small.copy(fontFamily = FontFamily.Monospace), color = c.muted, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.codeBg)
            .padding(horizontal = Space.s, vertical = 4.dp))
}

/** Everything that happened in one step, in a sheet that pulls up to full height and scrolls; × closes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StepSheet(s: Doing.StepView, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var lines by remember(s.step.id) { mutableStateOf(s.lines) }
    LaunchedEffect(s.step.id) {
        io(scope, { Doing.step(ctx, s.step.id).filter { it.kind == "line" } }) { got -> if (got.size >= lines.size) lines = got }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = c.bg, dragHandle = null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(s.step.text, style = Type.title, color = c.ink)
                    Text(listOf(s.step.who, s.step.repo.takeIf { it.isNotBlank() },
                        DateUtils.formatDateTime(ctx, s.step.at, DateUtils.FORMAT_SHOW_TIME)).filterNotNull().joinToString(" · "),
                        style = Type.small, color = c.muted)
                }
                RoundButton(Icons2.Close, "Close", c.field, c.ink, 40.dp, onClose)
            }
            if (s.step.detail.isNotBlank()) Box(Modifier.padding(top = Space.s)) { Mono(s.step.detail, 12) }
            if (s.step.model.isNotEmpty()) Text(Doing.describe(s.step.model, s.step.tokens), style = Type.small, color = c.blueInk,
                modifier = Modifier.padding(top = Space.xs))
        }
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = Space.l, end = Space.l, bottom = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (lines.isEmpty()) item { Text("Nothing more happened in this step.", style = Type.body, color = c.muted) }
            items(lines, key = { it.id }) { l ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    Text(DateUtils.formatDateTime(ctx, l.at, DateUtils.FORMAT_SHOW_TIME), style = Type.small, color = c.muted)
                    Text(l.text, style = Type.body, color = c.ink, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun LaneBubble(lane: Lane, on: Boolean, picked: Boolean, modifier: Modifier, onDoubleClick: (() -> Unit)? = null,
                       onClick: () -> Unit) {
    val c = LocalMochi.current
    val pulse by rememberInfiniteTransition(label = "lane").animateFloat(0.55f, 1f,
        infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val size: Dp = 60.dp
    Box(modifier.size(size).clip(CircleShape).background(if (on) lane.bg else c.field)
        .then(if (picked) Modifier.border(2.dp, c.ink, CircleShape) else if (on) Modifier.border(2.dp, lane.ink, CircleShape) else Modifier)
        .alpha(if (on) pulse else 0.55f)
        .combinedClickable(role = Role.Button, onDoubleClick = onDoubleClick, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(lane.label, style = Type.small.copy(fontWeight = if (on) Type.strong else null), color = if (on) lane.ink else c.muted,
            textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.widthIn(max = size - 8.dp))
    }
}
