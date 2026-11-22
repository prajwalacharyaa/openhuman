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

/*
 * The secondary screens reached from Home: all chats, Memory (what Mochi knows, the conversation log, uploaded
 * files), project maps and the Inbox. Split out of MainActivity.kt (2026-11-22) so each file stays readable.
 */

@Composable
private fun UploadsPage(snack: SnackbarHostState) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<JSONObject>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        io(scope, { Mochi.uploads(ctx) }, { loading = false; error = Room.friendly(it) }) {
            loading = false; items.clear(); items.addAll(it)
        }
    }
    fun remove(l: List<JSONObject>) = deleteWithUndo(scope, snack, if (l.size == 1) "File deleted" else "${l.size} files deleted", l,
        hide = { x -> items.removeAll { m -> x.any { it.optString("id") == m.optString("id") } } },
        restore = { items.addAll(0, it) },
        delete = { x -> Mochi.deleteUploads(ctx, x.map { it.optString("id") }) },
        failed = { error = "Couldn't delete: ${Room.friendly(it)}" })
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
internal fun ChatsScreen(onBack: () -> Unit) {
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
    fun load() = io(scope, { Mochi.threads(ctx) }, {
        if (all.isEmpty()) { all.addAll(Mochi.cachedThreads(ctx)); loaded = true }
        error = if (all.isEmpty()) "Can't reach Mochi. Is Tailscale on?" else "Offline: showing chats saved on this phone. Is Tailscale on?"
    }) {
        all.clear(); all.addAll(it); loaded = true; error = ""
    }
    fun remove(list: List<JSONObject>) = deleteWithUndo(scope, snack,
        if (list.size == 1) "Chat deleted" else "${list.size} chats deleted", list,
        hide = { l -> all.removeAll { t -> l.any { it.optString("id") == t.optString("id") } } },
        restore = { all.addAll(it); all.sortByDescending { t -> t.optString("lastMessageAt") } },
        delete = { l -> l.forEach { Mochi.deleteThread(ctx, it.optString("id")) } },
        failed = { error = "Couldn't delete: ${Room.friendly(it)}" })
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
            Refreshable({ load() }, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
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
internal fun MemoryScreen(onBack: () -> Unit) {
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
            { loading = false; error = "Memory: ${Room.friendly(it)}" }) { page ->
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
        failed = { error = "Couldn't forget: ${Room.friendly(it)}" })

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
                        Box(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp)).background(if (on) c.ink else c.field)
                            .clickable(role = Role.Tab) { scope.launch { pager.animateScrollToPage(i) } }.padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center) {
                            Text(label, style = Type.small.copy(fontWeight = if (on) Type.strong else null),
                                color = if (on) c.bg else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                if (text.isNotBlank()) io(scope, { Mochi.learn(ctx, text.trim()) }, { error = "Couldn't save: ${Room.friendly(it)}" }) {
                    scope.launch { pager.animateScrollToPage(0) }; restart()
                }
            }) { Text("Save") } },
            dismissButton = { TextButton({ teach = false }) { Text("Cancel") } })
    }
}

@Composable
internal fun MapsScreen(onBack: () -> Unit) {
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
                    .clickable(role = Role.Button) { MapActivity.open(ctx, p.optString("name")) }.padding(Space.l),
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
internal fun InboxScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    var notices by remember { mutableStateOf(Mochi.inbox(ctx)) }
    val team = remember { Agents.cached(ctx) }
    LaunchedEffect(Unit) { Mochi.markInboxSeen(ctx); MochiWidget.update(ctx, force = true) }
    // Clear keeps what it removed for a few seconds: one tap shouldn't lose an agent's long report
    var undo by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(undo) { if (undo != null) { delay(6_000); undo = null } }
    Screen("Inbox", onBack, action = {
        val held = undo
        if (held != null) Text("Undo", style = Type.small.copy(fontWeight = Type.strong), color = c.blueInk,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                Mochi.restoreInbox(ctx, held); notices = Mochi.inbox(ctx); undo = null
            }.heightIn(min = 44.dp).padding(Space.m))
        else if (notices.isNotEmpty()) Text("Clear", style = Type.small, color = c.muted,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                undo = Mochi.clearInbox(ctx); notices = emptyList()
            }.heightIn(min = 44.dp).padding(Space.m))
    }) {
        Refreshable({ notices = Mochi.inbox(ctx) }, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
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
}
