package app.mochi.phone

import app.mochi.phone.ui.LineIcon
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mochi.phone.ui.Field
import app.mochi.phone.ui.Icons2
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.MarkdownText
import app.mochi.phone.ui.Mascot
import app.mochi.phone.ui.MochiTheme
import app.mochi.phone.ui.Mood
import app.mochi.phone.ui.Pill
import app.mochi.phone.ui.RoundButton
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Type
import app.mochi.phone.ui.io
import app.mochi.phone.ui.WhileVisible
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mochi's agents: two pages you can swipe between. Team lists them (search; tap one to see what it's doing,
 * long-press for who it is, its notes and "Ask"); Doing shows the chosen agent's jobs, steps and tokens, like
 * "What Mochi is doing". Opened with an agent (from Home or a double-tap on "What Mochi is doing") it starts on Doing.
 */
class AgentsActivity : ComponentActivity() {
    private var requested by mutableStateOf<Pair<String, Long>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        take(intent)
        setContent { MochiTheme { AgentsScreen(requested) { finish() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(i: Intent?) {
        i?.getStringExtra(EXTRA_AGENT)?.let { requested = it to System.nanoTime() }
    }

    companion object {
        const val EXTRA_AGENT = "agent"

        /** Opens the screen (on that agent's Doing page if [agent] is given), or brings it to the front. */
        fun open(c: Context, agent: String? = null) = c.startActivity(Intent(c, AgentsActivity::class.java).apply {
            if (agent != null) putExtra(EXTRA_AGENT, agent)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentsScreen(requested: Pair<String, Long>?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var agents by remember { mutableStateOf(Agents.cached(ctx)) }
    var tokens by remember { mutableStateOf<Map<String, Doing.Tokens>>(emptyMap()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var selected by remember { mutableStateOf(requested?.first ?: agents.firstOrNull { Agents.working(it.name) }?.name
        ?: agents.firstOrNull()?.name) }
    var profile by remember { mutableStateOf<Agents.Agent?>(null) }
    val pager = rememberPagerState(initialPage = if (requested != null) 1 else 0) { 2 }
    LaunchedEffect(Unit) { io(scope, { Doing.backfill(ctx) }) {} }
    WhileVisible {
        var tick = 0
        while (true) {
            if (tick++ % 15 == 0) {
                io(scope, { Agents.load(ctx) }) { got ->
                    agents = got
                    if (selected == null) selected = got.firstOrNull()?.name
                }
                io(scope, { Agents.tokensToday(ctx) }) { tokens = it }
            }
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    LaunchedEffect(requested) {
        val r = requested ?: return@LaunchedEffect
        selected = r.first
        pager.animateScrollToPage(1)
    }
    val working = agents.filter { Agents.working(it.name, now) }

    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            RoundButton(Icons2.Back, "Back", c.field, c.ink, 44.dp, onBack)
            Column(Modifier.weight(1f).padding(horizontal = Space.xs)) {
                Text("Agents", style = Type.title, color = c.ink)
                Text(when {
                    agents.isEmpty() -> "Loading Mochi's team…"
                    working.isNotEmpty() -> working.joinToString(", ") { it.display } + " working"
                    else -> "${agents.size} agents · all resting"
                }, style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Mochi: what Mochi is doing (like the chat screen's Mochi)
            Mascot(Modifier.size(44.dp), mood = if (Doing.busy(now)) Mood.Thinking else Mood.Idle, label = "What Mochi is doing",
                onTap = { DoingActivity.open(ctx) })
            RoundButton(Icons2.Home, "Mochi home", c.field, c.ink, 44.dp) {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                onBack()
            }
        }
        // the two pages: a tap switches, a swipe works too
        Row(Modifier.padding(horizontal = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Pill("Team", pager.currentPage == 0, height = 36.dp) { scope.launch { pager.animateScrollToPage(0) } }
            Pill(selected?.let { Agents.display(it, agents) + " is doing" } ?: "Doing", pager.currentPage == 1,
                height = 36.dp) { scope.launch { pager.animateScrollToPage(1) } }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1) { page ->
            if (page == 0) {
                TeamPage(agents, tokens, now, onOpen = { a ->
                    selected = a.name
                    scope.launch { pager.animateScrollToPage(1) }
                }, onProfile = { profile = it })
            } else {
                val name = selected
                if (name == null) {
                    Text("No agents yet.", style = Type.body, color = c.muted, modifier = Modifier.padding(Space.xl))
                } else {
                    // one page slot is reused for whichever agent is picked: key it by the agent so its state (SY's
                    // hiring board, Nadia's LinkedIn page, an open step, the scroll) never carries over to the next one
                    key(name) {
                        AgentDoingPage(name, agents.firstOrNull { it.name == name }, tokens[name], now) {
                            agents.firstOrNull { a -> a.name == name }?.let { profile = it }
                        }
                    }
                }
            }
        }
    }

    profile?.let { a ->
        ProfileSheet(a, tokens[a.name], onClose = { profile = null }, onDoing = {
            profile = null
            selected = a.name
            scope.launch { pager.animateScrollToPage(1) }
        })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TeamPage(agents: List<Agents.Agent>, tokens: Map<String, Doing.Tokens>, now: Long,
                     onOpen: (Agents.Agent) -> Unit, onProfile: (Agents.Agent) -> Unit) {
    val c = LocalMochi.current
    val haptic = LocalHapticFeedback.current
    var query by remember { mutableStateOf("") }
    val shown = agents.filter { a ->
        query.isBlank() || listOf(a.display, a.role, a.description, a.name).any { it.contains(query.trim(), ignoreCase = true) }
    }.sortedByDescending { Agents.working(it.name, now) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        item("search") { Field(query, { query = it }, "Search agents", singleLine = true) }
        if (shown.isEmpty()) item("none") {
            Text(if (agents.isEmpty()) "No agents yet." else "No agent matches \"$query\".", style = Type.body,
                color = c.muted, modifier = Modifier.padding(Space.l))
        }
        items(shown, key = { it.name }) { a ->
            val busy = Agents.working(a.name, now)
            val last = Agents.latest(a.name)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card)
                .combinedClickable(role = Role.Button, onClickLabel = "See what ${a.display} is doing",
                    onLongClickLabel = "About ${a.display}", onClick = { onOpen(a) },
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onProfile(a) })
                .padding(Space.m), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Mascot(Modifier.size(60.dp), mood = if (busy) Mood.Thinking else Mood.Sleepy, look = a.look, label = a.display)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Text(a.display, style = Type.title, color = c.ink)
                        Text(a.role, style = Type.small, color = a.color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(when {
                        busy && last != null -> "Working: ${last.text}"
                        last != null -> "Resting · last active " + DateUtils.getRelativeTimeSpanString(last.at)
                        else -> "Resting"
                    }, style = Type.small, color = if (busy) c.ink else c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    tokens[a.name]?.takeIf { it.total > 0 }?.let {
                        Text("Today: ${Doing.fmt(it.total)} tokens", style = Type.small, color = c.muted)
                    }
                }
            }
        }
        item("hint") {
            Text("Tap an agent to see what it's doing; hold for who it is and what it remembers.",
                style = Type.small, color = c.muted, modifier = Modifier.padding(horizontal = Space.s))
        }
    }
}

/** One agent's work, like "What Mochi is doing": its jobs as cards, newest first; tap a step for everything in it. */
@Composable
private fun AgentDoingPage(name: String, agent: Agents.Agent?, tokens: Doing.Tokens?, now: Long, onProfile: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val busy = Agents.working(name, now)
    val last = Agents.latest(name)
    val tasks = Agents.tasks(name)
    val detailed = remember { Mochi.detailed(ctx) }
    var opened by remember { mutableStateOf<Doing.StepView?>(null) }
    val list = rememberLazyListState()
    // never moves on its own while December reads or taps (the board, a card): only when the view sits exactly on the
    // newest job does a newer one take its place (it would otherwise land just above the edge)
    val newest = tasks.firstOrNull()?.let { it.task?.id ?: it.steps.firstOrNull()?.step?.id }
    // SY (HR): her hiring board, refreshed every 10 s while it's open and right after a decision
    val scope = rememberCoroutineScope()
    var board by remember { mutableStateOf<Pair<List<org.json.JSONObject>, List<org.json.JSONObject>>?>(null) }
    var boardTick by remember { mutableStateOf(0) }
    if (agent?.hr == true) WhileVisible(name, boardTick) {
        while (true) {
            io(scope, { Agents.board(ctx) }) { board = it }
            delay(10_000)
        }
    }
    // the LinkedIn agent (whoever has the `linkedin` skill): December's drafts, approvals and posts
    var linkedin by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var linkedinTick by remember { mutableStateOf(0) }
    if (agent?.skills?.contains("linkedin") == true) WhileVisible(name, linkedinTick) {
        while (true) {
            io(scope, { LinkedIn.load(ctx) }) { linkedin = it }
            delay(15_000)
        }
    }
    // the trader (whoever has the `trading` skill, Samir): his book's summary, December's cards, the Research page
    var trading by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var tradingTick by remember { mutableStateOf(0) }
    if (agent?.skills?.contains("trading") == true) WhileVisible(name, tradingTick) {
        while (true) {
            io(scope, { Trading.load(ctx) }) { trading = it }
            delay(15_000)
        }
    }
    LaunchedEffect(newest) {
        val firstJob = 1 + (if (board != null && agent?.hr == true) 2 else 0) +
            (if (linkedin != null && agent?.skills?.contains("linkedin") == true) 2 else 0) +
            (if (trading != null && agent?.skills?.contains("trading") == true) 2 else 0)
        if (opened == null && !list.isScrollInProgress && list.firstVisibleItemIndex == firstJob &&
            list.firstVisibleItemScrollOffset < 24) list.scrollToItem(firstJob)
    }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s)) {
        item("who") {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).padding(Space.m),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Mascot(Modifier.size(84.dp), mood = if (busy) Mood.Thinking else Mood.Sleepy, look = agent?.look,
                    label = Agents.display(name, listOfNotNull(agent)), onTap = onProfile)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(agent?.display ?: name.replaceFirstChar { it.uppercase() }, style = Type.display, color = c.ink)
                    Text(when {
                        busy && last != null -> "Working: ${last.text}"
                        last != null -> "Resting · last active " + DateUtils.getRelativeTimeSpanString(last.at)
                        else -> "Resting"
                    }, style = Type.small, color = if (busy) c.ink else c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (tokens != null && tokens.total > 0) Text("Today: ${Doing.fmt(tokens.total)} tokens",
                        style = Type.small, color = c.muted)
                }
                if (agent != null) {
                    RoundButton(Icons2.Chat, "Chat with ${agent.display}", c.field, c.ink, 44.dp) {
                        ChatActivity.openAgent(ctx, agent.name)
                    }
                    // call the agent in its own voice (on a call already: pass the phone to it)
                    RoundButton(Icons2.Phone, "Call ${agent.display}", agent.color, Color.White, 44.dp) {
                        CallActivity.callAgent(ctx, agent.name) { err ->
                            if (err != null) android.widget.Toast.makeText(ctx, err, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        board?.takeIf { agent?.hr == true }?.let { (waiting, history) ->
            item("board") { HiringBoard(waiting, history) { scope.launch { delay(1_500); boardTick++ } } }
            item("board-jobs") {
                Text("${agent?.display ?: name}'s work", style = Type.title, color = c.ink, modifier = Modifier.padding(top = Space.m))
            }
        }
        trading?.takeIf { agent?.skills?.contains("trading") == true }?.let { tr ->
            item("trading") { TradingBoard(tr) { scope.launch { delay(2_000); tradingTick++ } } }
            item("trading-jobs") {
                Text("${agent?.display ?: name}'s work", style = Type.title, color = c.ink, modifier = Modifier.padding(top = Space.m))
            }
        }
        linkedin?.takeIf { agent?.skills?.contains("linkedin") == true }?.let { li ->
            item("linkedin") { LinkedInBoard(li) { scope.launch { delay(1_000); linkedinTick++ } } }
            item("linkedin-jobs") {
                Text("${agent?.display ?: name}'s work", style = Type.title, color = c.ink, modifier = Modifier.padding(top = Space.m))
            }
        }
        if (tasks.isEmpty()) item("empty") {
            Text("Nothing recent. Jobs show up here while ${agent?.display ?: name} works.", style = Type.body,
                color = c.muted, modifier = Modifier.padding(Space.l))
        }
        items(tasks, key = { it.task?.id ?: it.steps.firstOrNull()?.step?.id ?: it.at.toString() }) { t ->
            TaskCard(t, detailed) { opened = it }
        }
    }
    // floating mute while a call is on (every agent's page, as on Mochi's Doing screen)
    var muteTick by remember { mutableStateOf(0) }
    if (Call.active) Box(Modifier.align(Alignment.BottomEnd).padding(Space.xl).size(64.dp).clip(CircleShape)
        .background(if (Call.muted || Call.frozen) c.danger else c.ink)
        .clickable(role = Role.Button) { if (Call.frozen) Call.unfreeze() else Call.muted = !Call.muted; muteTick++ }
        .semantics { contentDescription = if (Call.muted) "Unmute" else "Mute" }, contentAlignment = Alignment.Center) {
        key(muteTick) { LineIcon(if (Call.muted || Call.frozen) Icons2.MicOff else Icons2.Mic, Color.White, 28.dp) }
    }
    }
    opened?.let { s -> StepSheet(s, onClose = { opened = null }) }
}

/** Who an agent is: role, what it does, model, skills, today's tokens, what it remembers; Ask and Doing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSheet(a: Agents.Agent, tokens: Doing.Tokens?, onClose: () -> Unit, onDoing: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = c.bg, dragHandle = null) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = Space.l, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Mascot(Modifier.size(72.dp), mood = Mood.Happy, look = a.look, label = a.display)
                Column(Modifier.weight(1f)) {
                    Text(a.display, style = Type.display, color = c.ink)
                    Text(a.role, style = Type.body, color = a.color)
                }
                RoundButton(Icons2.Close, "Close", c.field, c.ink, 40.dp, onClose)
            }
            Text(a.description, style = Type.body, color = c.ink)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                val chips = listOfNotNull(a.model.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }) +
                    a.skills.map { "Skill: $it" } +
                    listOfNotNull(tokens?.takeIf { it.total > 0 }?.let { "Today ${Doing.fmt(it.total)} tokens" })
                items(chips) { t ->
                    Text(t, style = Type.small, color = c.ink, modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .background(c.field).padding(horizontal = Space.s, vertical = 2.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Pill("Chat with ${a.display}", true, Modifier.weight(1f)) {
                    ChatActivity.openAgent(ctx, a.name)
                    onClose()
                }
                Pill("What ${a.display} is doing", false, Modifier.weight(1f), onClick = onDoing)
            }
            Text("What ${a.display} remembers", style = Type.title, color = c.ink, modifier = Modifier.padding(top = Space.s))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).padding(Space.m)) {
                if (a.notes.isBlank()) Text("Nothing yet. ${a.display} writes notes after each job.", style = Type.body,
                    color = c.muted)
                else MarkdownText(a.notes.trim(), c.ink)
            }
        }
    }
}
