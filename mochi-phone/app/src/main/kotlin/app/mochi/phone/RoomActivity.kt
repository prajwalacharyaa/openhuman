package app.mochi.phone

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mochi.phone.ui.Field
import app.mochi.phone.ui.Icons2
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.MarkdownText
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
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The room (the Roast Room on the server, mochi-room): December's business decisions argued out by an advisory
 * board. Designed for the phone: the meetings as cards, one meeting as verdict first then the rounds advisor by
 * advisor, the PDF, "Ask Mochi about this", and a guided way to put a new decision in (the six parts the room
 * works best with, read back before it's sent). Data from mochi-room-api (tailnet :8454).
 */
class RoomActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MochiTheme { RoomScreen(intent.getStringExtra(EXTRA_SESSION)) { finish() } } }
    }

    companion object { const val EXTRA_SESSION = "session" }
}

object Room {
    private fun base(c: Context) = "https://${Mochi.host(c)}:8454"

    private fun req(c: Context, path: String) =
        Request.Builder().url(base(c) + path).header("Authorization", "Bearer ${Mochi.token(c)}")

    fun get(c: Context, path: String): JSONObject = Mochi.http.newCall(req(c, path).build()).execute().use { r ->
        val text = r.body?.string() ?: "{}"
        if (!r.isSuccessful) throw IllegalStateException(runCatching { JSONObject(text).optString("error") }.getOrNull()
            ?.ifBlank { null } ?: "HTTP ${r.code}")
        JSONObject(text)
    }

    /** Queues a decision. Returns the error text, or null when the room took it. */
    fun ask(c: Context, decision: String, full: Boolean): String? {
        val body = JSONObject().put("decision", decision).put("mode", if (full) "full" else "quick").toString()
        Mochi.http.newCall(req(c, "/ask").post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            if (r.isSuccessful) return null
            val text = r.body?.string() ?: ""
            return runCatching { JSONObject(text).optString("error") }.getOrNull()?.ifBlank { null } ?: "HTTP ${r.code}"
        }
    }

    private val portraits = ConcurrentHashMap<String, ImageBitmap>()
    private val noPortrait = ConcurrentHashMap.newKeySet<String>()

    fun portrait(c: Context, seat: String): ImageBitmap? {
        portraits[seat]?.let { return it }
        if (seat in noPortrait) return null
        val bmp = runCatching {
            Mochi.http.newCall(req(c, "/portraits/$seat").build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val bytes = r.body?.bytes() ?: return@use null
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
            }
        }.getOrNull()
        if (bmp == null) noPortrait.add(seat) else portraits[seat] = bmp
        return bmp
    }

    /** The verdict PDF (made on the server), saved to Downloads and opened. Returns what to tell December. */
    fun pdf(c: Context, id: String, title: String): String {
        val bytes = Mochi.http.newCall(req(c, "/sessions/$id/pdf").build()).execute().use { r ->
            if (!r.isSuccessful) return "No PDF yet: the meeting has no verdict."
            r.body?.bytes() ?: return "Couldn't get the PDF."
        }
        val name = "Room - " + title.replace(Regex("[^A-Za-z0-9 ,.-]"), "").take(60).trim() + ".pdf"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
        }
        val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return "Couldn't save the PDF."
        c.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return "Couldn't save the PDF."
        runCatching {
            c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return "Saved to Downloads: $name"
    }

    val PARTS = listOf(
        Triple("decision", "The decision", "Should I…? One line."),
        Triple("options", "Your options", "Two or three. \"Keep doing what I do now\" counts."),
        Triple("numbers", "Your numbers", "Revenue, cash in the bank, team size."),
        Triple("limits", "Your limits", "Deadline, hours you can give, budget."),
        Triple("success", "What success looks like", "And by when."),
        Triple("lean", "Which way you lean", "And why. The room tests it instead of agreeing."),
    )
    const val EXAMPLE = "I run a coaching business at \$30k a month with 15 clients at \$2,000 each. Should I raise to " +
        "\$3,500 for everyone, raise for new clients only, or keep \$2,000? Close rate is about 50% and I have \$40k in " +
        "the bank. Success is \$45k a month by March. I'm leaning toward raising for everyone because I'm underpaid."

    /** The six answers as one brief in the repo's example shape (plain sentences, in December's words). */
    fun brief(parts: Map<String, String>): String = PARTS.mapNotNull { (key, _, _) ->
        parts[key]?.trim()?.trimEnd('.')?.ifBlank { null }?.let { text ->
            when (key) {
                "options" -> "My options: $text."
                "success" -> if (text.lowercase().startsWith("success")) "$text." else "Success is $text."
                "lean" -> if (text.lowercase().startsWith("i")) "$text." else "I'm leaning toward $text."
                else -> "$text."
            }
        }
    }.joinToString(" ")
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

@Composable
private fun labelColors(label: String): Pair<Color, Color> {
    val c = LocalMochi.current
    val l = label.uppercase()
    return when {
        l.startsWith("DON") -> c.danger.copy(alpha = 0.14f) to c.danger
        l.startsWith("RESHAPE") || l.startsWith("WAIT") -> c.amberBg to c.amberInk
        l.isBlank() -> c.field to c.muted
        else -> c.greenBg to c.greenInk
    }
}

@Composable
private fun VerdictChip(label: String) {
    val (bg, ink) = labelColors(label)
    Text(label.ifBlank { "In progress" }, style = Type.small.copy(fontWeight = FontWeight.Bold), color = ink,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = Space.s, vertical = 3.dp))
}

@Composable
private fun Portrait(seat: String, name: String, color: Color, size: androidx.compose.ui.unit.Dp) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var img by remember(seat) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(seat) { io(scope, { Room.portrait(ctx, seat) }) { img = it } }
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.25f)).border(2.dp, color, CircleShape),
        contentAlignment = Alignment.Center) {
        val i = img
        if (i != null) Image(i, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString(""),
            style = Type.small.copy(fontWeight = FontWeight.Bold), color = LocalMochi.current.ink)
    }
}

private fun seatColor(info: JSONObject?): Color =
    runCatching { Color(android.graphics.Color.parseColor(info?.optString("color") ?: "")) }.getOrDefault(Color(0xFF8A94A6))

@Composable
private fun Card(modifier: Modifier = Modifier, bg: Color? = null, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val c = LocalMochi.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg ?: c.card)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) { content() }
}

@Composable
private fun Heading(text: String) =
    Text(text, style = Type.title, color = LocalMochi.current.ink, modifier = Modifier.padding(top = Space.m))

@Composable
private fun RoomScreen(startSession: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    var open by remember { mutableStateOf(startSession) }
    var asking by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf("") }

    BackHandler(enabled = open != null || asking) { if (asking) asking = false else open = null }
    LaunchedEffect(toast) {
        if (toast.isNotEmpty()) { Toast.makeText(ctx, toast, Toast.LENGTH_LONG).show(); toast = "" }
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            RoundButton(Icons2.Back, "Back", c.field, c.ink, 44.dp) {
                when { asking -> asking = false; open != null -> open = null; else -> onBack() }
            }
            Text(when { asking -> "Ask the room"; open != null -> "The meeting"; else -> "The room" },
                style = Type.display, color = c.ink, modifier = Modifier.weight(1f))
        }
        when {
            asking -> AskPane(onSent = { msg -> asking = false; toast = msg })
            open != null -> MeetingPane(open!!) { toast = it }
            else -> ListPane(onOpen = { open = it }, onAsk = { asking = true })
        }
    }
}

@Composable
private fun ListPane(onOpen: (String) -> Unit, onAsk: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf("") }

    WhileVisible {
        while (true) {
            io(scope, { Room.get(ctx, "/sessions") }, { error = "Couldn't reach the room: ${it.message}" }) { data = it; error = "" }
            delay(15_000)
        }
    }
    val sessions = data?.optJSONArray("sessions").objects()
    val running = data?.optJSONObject("status")?.optJSONObject("running")
    val waiting = data?.optJSONObject("status")?.optInt("waiting") ?: 0

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Space.l, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.m)) {
        item {
            Text("Your business decisions, argued out by an advisory board built from Bezos, Paul Graham, Hormozi, " +
                "Buffett and others. You get a scored verdict, the dissent and next steps.", style = Type.small, color = c.muted)
        }
        item {
            Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(c.ink)
                .clickable(role = Role.Button, onClick = onAsk), contentAlignment = Alignment.Center) {
                Text("Put a decision to the room", style = Type.title, color = c.bg)
            }
        }
        if (running != null) item {
            Card(bg = c.blueBg, onClick = running.optString("session").ifBlank { null }?.let { sid -> { onOpen(sid) } }) {
                Text("Meeting now", style = Type.small.copy(fontWeight = FontWeight.Bold), color = c.blueInk)
                Text(running.optString("decision"), style = Type.body, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val rounds = if (running.has("rounds_done")) " · ${running.optInt("rounds_done")} round(s) in" else ""
                Text("${if (running.optString("mode") == "full") "Full room" else "Room"} · ${running.optInt("minutes")} min$rounds" +
                    if (waiting > 0) " · $waiting waiting" else "", style = Type.small, color = c.muted)
            }
        }
        if (error.isNotEmpty()) item { Text(error, style = Type.small, color = c.danger) }
        if (data != null && sessions.isEmpty()) item {
            Text("No meetings yet. Put a decision to the room, or say \"put this in the room\" to Mochi.",
                style = Type.body, color = c.muted)
        }
        items(sessions, key = { it.optString("id") }) { s ->
            Card(onClick = { onOpen(s.optString("id")) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    VerdictChip(s.optString("label"))
                    Spacer(Modifier.weight(1f))
                    Text("${s.optString("date")} · ${if (s.optString("mode") == "full") "full room" else "room"}",
                        style = Type.small, color = c.muted)
                }
                Text(s.optString("title"), style = Type.title, color = c.ink)
                if (s.optString("call").isNotBlank())
                    Text(s.optString("call"), style = Type.small, color = c.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row {   // the advisors at the table, slightly overlapping
                    s.optJSONArray("seats").strings().forEachIndexed { i, seat ->
                        Box(Modifier.offset(x = (-8 * i).dp)) { Portrait(seat, seat, Color(0xFF8A94A6), 28.dp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeetingPane(id: String, say: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var s by remember(id) { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf("") }
    var round by remember(id) { mutableStateOf(-1) }
    var expanded by remember(id) { mutableStateOf(setOf<String>()) }
    var showReport by remember(id) { mutableStateOf(false) }
    var showBrief by remember(id) { mutableStateOf(false) }
    var making by remember { mutableStateOf(false) }

    WhileVisible(id) {
        while (true) {
            io(scope, { Room.get(ctx, "/sessions/$id") }, { error = "Couldn't load the meeting: ${it.message}" }) { s = it; error = "" }
            delay(if (s?.optBoolean("done") == true) 120_000 else 15_000)   // a meeting that's on: follow it
        }
    }
    val m = s
    if (m == null) {
        Text(error.ifBlank { "Loading…" }, style = Type.body, color = if (error.isEmpty()) c.muted else c.danger,
            modifier = Modifier.padding(Space.xl))
        return
    }
    val info = m.optJSONObject("seat_info") ?: JSONObject()
    val v = m.optJSONObject("verdict")
    val rounds = m.optJSONArray("rounds").objects()
    val shown = if (round in rounds.indices) round else rounds.lastIndex
    val title = m.optString("title")

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Space.l, end = Space.l, bottom = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.m)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    VerdictChip(m.optString("label"))
                    Text("${m.optString("date")} · ${if (m.optString("mode") == "full") "full room" else "room"}",
                        style = Type.small, color = c.muted)
                }
                Text(title, style = Type.display, color = c.ink)
                if (m.optString("decision").isNotBlank()) Text(m.optString("decision"), style = Type.body, color = c.muted)
            }
        }
        if (v == null) item {
            Card(bg = c.blueBg) {
                Text("The meeting is on", style = Type.title, color = c.blueInk)
                Text("${rounds.size} round(s) in. The verdict comes when the Chair has scored the room; this page follows it.",
                    style = Type.small, color = c.ink)
            }
        }
        if (v != null) {
            item {
                val (bg, ink) = labelColors(v.optString("label"))
                Card(bg = bg) {
                    Text("The call", style = Type.small.copy(fontWeight = FontWeight.Bold), color = ink)
                    Text(v.optString("call"), style = Type.body.copy(fontWeight = Type.strong), color = c.ink)
                }
            }
            val scores = m.optJSONArray("scores").objects()
            if (scores.isNotEmpty()) item {
                Card {
                    Text("How the options scored", style = Type.title, color = c.ink)
                    for (o in scores) {
                        val score = if (o.isNull("score")) 0.0 else o.optDouble("score")
                        val top = o.optString("id") == v.optString("recommended")
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row {
                                Text("${o.optString("id")} · ${o.optString("label")}", style = Type.small.copy(
                                    fontWeight = if (top) FontWeight.Bold else FontWeight.Normal), color = c.ink,
                                    modifier = Modifier.weight(1f))
                                Text(if (o.isNull("score")) "–" else "%.1f".format(score), style = Type.small, color = c.ink)
                            }
                            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.field)) {
                                Box(Modifier.fillMaxWidth((score / 10.0).toFloat().coerceIn(0f, 1f)).fillMaxHeight()
                                    .clip(RoundedCornerShape(4.dp)).background(if (top) c.greenInk else c.muted))
                            }
                        }
                    }
                }
            }
            val why = v.optJSONArray("why").objects()
            if (why.isNotEmpty()) {
                item { Heading("Why the room landed here") }
                items(why) { w ->
                    val key = seatKeyOf(w.optString("seat"), info)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        Portrait(key ?: w.optString("seat"), w.optString("seat"), seatColor(info.optJSONObject(key ?: "")), 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(w.optString("seat") + (w.optString("framework").ifBlank { null }?.let { " · $it" } ?: ""),
                                style = Type.small.copy(fontWeight = FontWeight.Bold), color = c.ink)
                            Text(w.optString("point"), style = Type.body, color = c.ink)
                        }
                    }
                }
            }
            v.optJSONObject("minority")?.takeIf { it.optString("seat").isNotBlank() }?.let { mino ->
                item {
                    Card(bg = c.amberBg) {
                        Text("The strongest objection · ${mino.optString("seat")}", style = Type.small.copy(fontWeight = FontWeight.Bold),
                            color = c.amberInk)
                        Text(mino.optString("quote"), style = Type.body, color = c.ink)
                        if (mino.optString("would_have_to_be_true").isNotBlank())
                            Text("For them to be right: ${mino.optString("would_have_to_be_true")}", style = Type.small, color = c.muted)
                    }
                }
            }
            val fights = v.optJSONArray("fights").objects()
            if (fights.isNotEmpty()) {
                item { Heading("Where they fought") }
                items(fights) { f ->
                    Card {
                        Text(f.optString("crux"), style = Type.body.copy(fontWeight = Type.strong), color = c.ink)
                        Text(f.optString("story"), style = Type.small, color = c.muted)
                    }
                }
            }
            val steps = v.optJSONArray("next_steps").objects()
            if (steps.isNotEmpty()) {
                item { Heading("What to do next") }
                items(steps) { n ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        Box(Modifier.padding(top = 7.dp).size(8.dp).clip(CircleShape).background(c.greenInk))
                        Column(Modifier.weight(1f)) {
                            if (n.optString("when").isNotBlank()) Text(n.optString("when"), style = Type.small, color = c.muted)
                            Text(n.optString("action"), style = Type.body, color = c.ink)
                        }
                    }
                }
            }
            val conditions = v.optJSONArray("conditions").strings()
            item {
                Card {
                    if (conditions.isNotEmpty()) {
                        Text("Conditions", style = Type.title, color = c.ink)
                        for (cond in conditions) Text("• $cond", style = Type.small, color = c.ink)
                    }
                    for ((k, label) in listOf("cheapest_test" to "Cheapest test", "kill_criteria" to "Stop if", "revisit" to "Revisit")) {
                        if (v.optString(k).isNotBlank()) {
                            Text(label, style = Type.small.copy(fontWeight = FontWeight.Bold), color = c.ink)
                            Text(v.optString(k), style = Type.small, color = c.muted)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Pill(if (making) "Making PDF…" else "PDF", true, Modifier.weight(1f)) {
                        if (making) return@Pill
                        making = true
                        io(scope, { Room.pdf(ctx, id, title) }, { making = false; say("Couldn't get the PDF.") }) { making = false; say(it) }
                    }
                    Pill("Ask Mochi about this", false, Modifier.weight(1.4f)) {
                        MainActivity.openChat(ctx, new = true,
                            text = "About the room's meeting \"$title\" (${m.optString("date")}): what do you think?", send = false)
                    }
                }
            }
        }
        if (rounds.isNotEmpty()) {
            item { Heading("The rounds") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    rounds.forEachIndexed { i, r ->
                        Pill("Round ${r.optInt("n")}", i == shown, Modifier.weight(1f), height = 36.dp) { round = i }
                    }
                }
            }
            val r = rounds[shown]
            items(r.optJSONArray("cards").objects(), key = { "${r.optInt("n")}-${it.optString("seat")}-${it.optString("name")}" }) { card ->
                val key = card.optString("seat")
                val seat = info.optJSONObject(key)
                val open = "${r.optInt("n")}-$key" in expanded
                Card(onClick = { expanded = if (open) expanded - "${r.optInt("n")}-$key" else expanded + "${r.optInt("n")}-$key" }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        Portrait(key, card.optString("name"), seatColor(seat), 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text(seat?.optString("name") ?: card.optString("name"), style = Type.body.copy(fontWeight = Type.strong), color = c.ink)
                            Text(seat?.optString("pillar") ?: "", style = Type.small, color = c.muted)
                        }
                        if (card.optString("stance").isNotBlank()) VerdictChipPlain(card.optString("stance"))
                    }
                    val sc = card.optJSONObject("scores")
                    if (sc != null && sc.length() > 0) Text(sc.keys().asSequence().sorted()
                        .joinToString("   ") { "$it ${"%.0f".format(sc.optDouble(it))}" }, style = Type.small, color = c.muted)
                    if (card.optString("call").isNotBlank()) Text(card.optString("call"), style = Type.body, color = c.ink)
                    if (open) MarkdownText(card.optString("text"), c.ink)
                    else Text("Tap for the whole card", style = Type.small, color = c.muted)
                }
            }
            if (r.optString("chair").isNotBlank()) item {
                val key = "chair-${r.optInt("n")}"
                val open = key in expanded
                Card(bg = c.field, onClick = { expanded = if (open) expanded - key else expanded + key }) {
                    Text("The Chair's notes", style = Type.body.copy(fontWeight = Type.strong), color = c.ink)
                    if (open) MarkdownText(r.optString("chair"), c.ink)
                    else Text(r.optString("chair").replace(Regex("[*#]"), "").take(160) + "…", style = Type.small, color = c.muted)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(top = Space.m)) {
                Pill(if (showBrief) "Hide the brief" else "The brief", false, Modifier.weight(1f), height = 40.dp) { showBrief = !showBrief }
                if (m.optString("report").isNotBlank())
                    Pill(if (showReport) "Hide the report" else "Full report", false, Modifier.weight(1f), height = 40.dp) { showReport = !showReport }
            }
        }
        if (showBrief) item { Card { MarkdownText(m.optString("brief"), c.ink) } }
        if (showReport) item { Card { MarkdownText(m.optString("report"), c.ink) } }
        item {
            Text("The advisors are simulations built from each person's public work, not the people themselves.",
                style = Type.small, color = c.muted)
        }
    }
}

@Composable
private fun VerdictChipPlain(text: String) {
    val c = LocalMochi.current
    Text(text, style = Type.small, color = c.ink, maxLines = 1,
        modifier = Modifier.width(110.dp).clip(RoundedCornerShape(8.dp)).background(c.field).padding(horizontal = Space.s, vertical = 3.dp),
        overflow = TextOverflow.Ellipsis)
}

private fun seatKeyOf(name: String, info: JSONObject): String? {
    val t = name.trim().lowercase()
    for (k in info.keys()) {
        val s = info.optJSONObject(k) ?: continue
        if (t == k || t == s.optString("short").lowercase() || t == s.optString("name").lowercase() ||
            t == s.optString("name").substringAfterLast(" ").lowercase()) return k
    }
    return null
}

@Composable
private fun AskPane(onSent: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    val answers = remember { mutableStateOf(mapOf<String, String>()) }
    var full by remember { mutableStateOf(false) }
    var showExample by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Space.l, end = Space.l, bottom = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.m)) {
        val text = review
        if (text == null) {
            item {
                Text("The room works best with these six parts, in your own words. Leave one empty and the room assumes it.",
                    style = Type.small, color = c.muted)
            }
            item {
                Card(bg = c.field, onClick = { showExample = !showExample }) {
                    Text(if (showExample) "An example" else "See an example", style = Type.body.copy(fontWeight = Type.strong), color = c.ink)
                    if (showExample) Text(Room.EXAMPLE, style = Type.small, color = c.ink)
                }
            }
            items(Room.PARTS, key = { it.first }) { (key, label, hint) ->
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(label, style = Type.body.copy(fontWeight = Type.strong), color = c.ink)
                    Field(answers.value[key] ?: "", { answers.value = answers.value + (key to it) }, hint)
                }
            }
            item { ModePicker(full) { full = it } }
            item {
                val ready = (answers.value["decision"] ?: "").isNotBlank()
                Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(if (ready) c.ink else c.field)
                    .clickable(role = Role.Button, enabled = ready) { review = Room.brief(answers.value); error = "" },
                    contentAlignment = Alignment.Center) {
                    Text("Review", style = Type.title, color = if (ready) c.bg else c.muted)
                }
            }
        } else {
            item {
                Text("This is what goes in, exactly. Change anything, then send. Nothing else from Mochi goes with it.",
                    style = Type.small, color = c.muted)
            }
            item { Field(text, { review = it }, "Your decision") }
            item { ModePicker(full) { full = it } }
            if (error.isNotEmpty()) item { Text(error, style = Type.small, color = c.danger) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Pill("Edit the parts", false, Modifier.weight(1f)) { review = null }
                    Pill(if (sending) "Sending…" else if (full) "Send to the full room" else "Send to the room", true, Modifier.weight(1.4f)) {
                        if (sending) return@Pill
                        if (text.trim().length < 15) { error = "Say a bit more: the options and your numbers."; return@Pill }
                        sending = true
                        io(scope, { Room.ask(ctx, text.trim(), full) }, { sending = false; error = "Couldn't reach the room." }) { err ->
                            sending = false
                            if (err == null) onSent(if (full) "The full room is meeting: about 30 to 60 minutes. A notification brings the verdict."
                                else "The room is meeting: about 10 to 20 minutes. A notification brings the verdict.")
                            else error = err
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModePicker(full: Boolean, set: (Boolean) -> Unit) {
    val c = LocalMochi.current
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Pill("Room", !full, Modifier.weight(1f), height = 40.dp) { set(false) }
            Pill("Full room", full, Modifier.weight(1f), height = 40.dp) { set(true) }
        }
        Text(if (full) "The whole board, three rounds, the detailed report. 30 to 60 minutes and a big part of the week's " +
            "Claude use: for big calls that are hard to undo." else "Four advisors, two rounds, 10 to 20 minutes. For " +
            "everyday and medium decisions.", style = Type.small, color = c.muted)
    }
}
