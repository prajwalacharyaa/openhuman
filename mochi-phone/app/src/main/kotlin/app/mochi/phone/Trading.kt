package app.mochi.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.mochi.phone.ui.Icons2
import app.mochi.phone.ui.LineIcon
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.MochiColors
import app.mochi.phone.ui.MochiTheme
import app.mochi.phone.ui.Pill
import app.mochi.phone.ui.RoundButton
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Type
import app.mochi.phone.ui.WhileVisible
import app.mochi.phone.ui.io
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Samir's book on the server (mochi-trade's snapshot through mochi-ring), and December's taps on it. */
object Trading {
    fun load(c: Context): JSONObject = Doing.get(c, "/trading")
    fun stock(c: Context, symbol: String): JSONObject = Doing.get(c, "/trading/stock/${symbol.uppercase()}")
    fun settings(c: Context): JSONObject = Doing.get(c, "/trading/settings").optJSONObject("settings") ?: JSONObject()

    private fun post(c: Context, path: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url("${Mochi.ringUrl(c)}$path")
            .header("Authorization", "Bearer ${Mochi.token(c)}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        Mochi.http.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) throw IllegalStateException(
                runCatching { JSONObject(text).optString("error") }.getOrNull()?.ifBlank { null } ?: "HTTP ${r.code}")
            return runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        }
    }

    fun saveSettings(c: Context, s: JSONObject): JSONObject = post(c, "/trading/settings", JSONObject().put("settings", s))
    fun act(c: Context, kind: String, vararg extra: Pair<String, Any?>): JSONObject {
        val b = JSONObject().put("kind", kind)
        extra.forEach { (k, v) -> if (v != null) b.put(k, v) }
        return post(c, "/trading/action", b)
    }
}

// ---- small formatting ----

/** Rs with Nepali grouping: 4,82,300. */
internal fun rs(x: Double, decimals: Boolean = false): String {
    val neg = x < 0
    val v = abs(x)
    val whole = v.toLong()
    val s = whole.toString()
    val grouped = if (s.length <= 3) s else {
        val head = s.dropLast(3)
        head.reversed().chunked(2).joinToString(",").reversed() + "," + s.takeLast(3)
    }
    val frac = if (decimals) "." + "%02d".format(((v - whole) * 100).toLong()) else ""
    return (if (neg) "−" else "") + "Rs " + grouped + frac
}

internal fun pct(x: Double?): String = when {
    x == null -> "–"
    x > 0 -> "▲ +%.1f%%".format(x)
    x < 0 -> "▼ −%.1f%%".format(-x)
    else -> "0.0%"
}

internal fun upDown(c: MochiColors, x: Double?): Color = when {
    x == null || x == 0.0 -> c.muted
    x > 0 -> c.greenInk
    else -> c.danger
}

private fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k) else null
private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

// ---- Samir's board on his agent screen: one glance, then act on the cards ----

@Composable
internal fun TradingBoard(data: JSONObject, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val snap = data.optJSONObject("snapshot") ?: return Text("Samir's book isn't ready yet.", style = Type.small, color = c.muted)
    val mode = snap.optString("mode", "paper")
    val b = snap.optJSONObject("books")?.optJSONObject(mode) ?: JSONObject()
    val nepse = data.optJSONObject("market")?.optJSONArray("indices").objs().firstOrNull { it.optString("name") == "NEPSE Index" }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        SummaryStrip(mode, b, nepse?.dbl("pct"))
        val cards = snap.optJSONArray("cards").objs()
        if (cards.isNotEmpty()) Text("Needs you (${cards.size})", style = Type.title, color = c.ink, modifier = Modifier.padding(top = Space.s))
        cards.forEach { TradeCard(it, onChanged) }
        Pill("Research ▸", true, Modifier.fillMaxWidth()) { ctx.startActivity(Intent(ctx, ResearchActivity::class.java)) }
    }
}

@Composable
private fun SummaryStrip(mode: String, b: JSONObject, nepsePct: Double?) {
    val c = LocalMochi.current
    val paper = mode == "paper"
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(if (paper) c.blueBg else c.greenBg).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(rs(b.optDouble("value", 0.0)), style = Type.display, color = c.ink, modifier = Modifier.weight(1f))
            Text(if (paper) "PAPER" else "LIVE", style = Type.small.copy(fontWeight = Type.strong),
                color = if (paper) c.blueInk else c.greenInk)
        }
        val pnl = b.optDouble("pnl", 0.0)
        Text("${if (pnl >= 0) "▲ +" else "▼ "}${rs(pnl)} (${pct(b.dbl("pnl_pct")).replace("▲ ", "").replace("▼ ", "")}) all time",
            style = Type.body, color = upDown(c, pnl))
        Text("today ${pct(if (b.optDouble("value", 0.0) > 0) b.optDouble("today", 0.0) / b.optDouble("value") * 100 else null)} · " +
            "limit ${rs(b.optDouble("collateral", 0.0))}" + (nepsePct?.let { " · NEPSE ${pct(it)}" } ?: ""),
            style = Type.small, color = c.muted)
    }
}

/** One card December acts on: a ready ticket, Samir's suggestion, or the EDIS reminder. */
@Composable
internal fun TradeCard(k: JSONObject, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var placing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    fun send(kind: String, vararg extra: Pair<String, Any?>) {
        busy = true
        io(scope, { Trading.act(ctx, kind, "id" to k.optInt("id"), *extra) }, { busy = false }) { busy = false; onChanged() }
    }
    val type = k.optString("type")
    val (title, accent) = when (type) {
        "ticket" -> "${k.optString("side").uppercase()} ${k.optInt("qty")} ${k.optString("symbol")} @ ${k.optDouble("price")}" to
            (if (k.optString("side") == "sell") c.danger else c.greenInk)
        "suggestion" -> "WATCH ${k.optString("symbol")} ${k.optString("op")} ${k.optDouble("price")} → ${k.optString("action")}" +
            (if (k.optBoolean("stoploss")) " (stop-loss)" else "") to c.blueInk
        else -> "⚠ ${k.optString("text")}" to c.amberInk
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).clickable { open = true }.padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(title, style = Type.body.copy(fontWeight = Type.strong), color = accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = when (type) {
            "ticket" -> "${k.optString("mode").uppercase()} · fees ${rs(k.optDouble("fees"), true)} · ${k.optString("why")}"
            "suggestion" -> "${k.optString("mode").uppercase()} · ${k.optString("why")}"
            else -> "Stays here until it's done."
        }
        Text(sub, style = Type.small, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            when (type) {
                "ticket" -> {
                    Pill(if (k.optString("mode") == "live") "I placed it" else "Fill (paper)", true, Modifier.weight(1f), 40.dp) {
                        if (!busy) { if (k.optString("mode") == "live") placing = true else send("placed") }
                    }
                    Pill("Not now", false, Modifier.weight(1f), 40.dp) { if (!busy) send("notnow") }
                }
                "suggestion" -> {
                    Pill("Approve", true, Modifier.weight(1f), 40.dp) { if (!busy) send("approve") }
                    Pill("Reject", false, Modifier.weight(1f), 40.dp) { if (!busy) send("reject") }
                }
                else -> {
                    Pill("Done", true, Modifier.weight(1f), 40.dp) { if (!busy) send("done") }
                    Pill("Remind in 1 h", false, Modifier.weight(1f), 40.dp) { if (!busy) send("snooze") }
                }
            }
        }
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, confirmButton = { TextButton({ open = false }) { Text("Close") } },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(k.optString("why").ifBlank { k.optString("text") }, style = Type.body)
                if (type == "ticket") {
                    Text("Amount ${rs(k.optDouble("amount"), true)} · fees ${rs(k.optDouble("fees"), true)}", style = Type.small)
                    if (k.optString("stop").isNotBlank() && k.optString("stop") != "null") Text("Stop-loss ${k.optString("stop")}", style = Type.small)
                    if (k.optString("target").isNotBlank() && k.optString("target") != "null") Text("Target ${k.optString("target")}", style = Type.small)
                    Text("Place it in TMS yourself, then tap \"I placed it\" with the real price and quantity. It expires at the close.",
                        style = Type.small, color = c.muted)
                }
                Text("Made ${k.optString("created").replace('T', ' ')}", style = Type.small, color = c.muted)
            }
        })
    if (placing) PlacedDialog(k, onDismiss = { placing = false }) { price, qty ->
        placing = false
        send("placed", "price" to price, "qty" to qty)
    }
}

/** "I placed it": the price and quantity as they really filled in TMS (partial fills happen). */
@Composable
private fun PlacedDialog(k: JSONObject, onDismiss: () -> Unit, onDone: (Double, Int) -> Unit) {
    var price by remember { mutableStateOf(k.optDouble("price").toString()) }
    var qty by remember { mutableStateOf(k.optInt("qty").toString()) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Placed in TMS: ${k.optString("side")} ${k.optString("symbol")}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text("As it really filled:", style = Type.small)
                app.mochi.phone.ui.Field(price, { price = it }, "Price", singleLine = true)
                app.mochi.phone.ui.Field(qty, { qty = it }, "Quantity", singleLine = true)
            }
        },
        confirmButton = {
            TextButton({ val p = price.toDoubleOrNull(); val q = qty.toIntOrNull(); if (p != null && q != null && q > 0) onDone(p, q) }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

// ---- the Research page: swipe tabs, back and home, floating mute ----

class ResearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MochiTheme { ResearchScreen(onBack = { finish() }, onHome = {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }) } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResearchScreen(onBack: () -> Unit, onHome: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var stock by remember { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf<String?>(null) }   // which book this page shows (default: the settings' mode)
    WhileVisible(tick) {
        while (true) { io(scope, { Trading.load(ctx) }) { data = it }; delay(15_000) }
    }
    val tabs = listOf("Holdings", "Watchlist", "Market", "Journal", "Calendar")
    val pager = rememberPagerState { tabs.size }
    val snap = data?.optJSONObject("snapshot")
    val mode = view ?: snap?.optString("mode") ?: "paper"
    Box(Modifier.fillMaxSize().background(c.bg).systemBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons2.Back, "Back", c.field, c.ink, 44.dp, onBack)
                Text("Samir · Research", style = Type.title, color = c.ink, modifier = Modifier.weight(1f).padding(horizontal = Space.m))
                Text(if (mode == "paper") "PAPER" else "LIVE", style = Type.small.copy(fontWeight = Type.strong),
                    color = if (mode == "paper") c.blueInk else c.greenInk,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (mode == "paper") c.blueBg else c.greenBg)
                        .clickable { view = if (mode == "paper") "live" else "paper" }.padding(horizontal = Space.m, vertical = Space.xs))
                Spacer(Modifier.width(Space.s))
                RoundButton(Icons2.Home, "Home", c.field, c.ink, 44.dp, onHome)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.l),
                horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                tabs.forEachIndexed { i, t ->
                    val on = pager.currentPage == i
                    Text(t, style = Type.body.copy(fontWeight = if (on) Type.strong else null), color = if (on) c.bg else c.ink,
                        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) c.ink else c.field)
                            .clickable { scope.launch { pager.animateScrollToPage(i) } }.padding(horizontal = Space.l, vertical = Space.s))
                }
            }
            HorizontalPager(pager, Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) { page ->
                if (data == null) Text("Loading…", style = Type.body, color = c.muted, modifier = Modifier.padding(Space.xl))
                else when (page) {
                    0 -> HoldingsTab(data!!, mode, { stock = it }) { scope.launch { delay(2_000); tick++ } }
                    1 -> WatchTab(data!!, { stock = it }) { scope.launch { delay(2_000); tick++ } }
                    2 -> MarketTab(data!!) { stock = it }
                    3 -> JournalTab(data!!, mode)
                    else -> CalendarTab(data!!) { stock = it }
                }
            }
        }
        // floating mute while a call is on (as on the Doing screen)
        if (Call.active) Box(Modifier.align(Alignment.BottomEnd).padding(Space.xl).size(64.dp).clip(CircleShape)
            .background(if (Call.muted || Call.frozen) c.danger else c.ink)
            .clickable(role = Role.Button) { if (Call.frozen) Call.unfreeze() else Call.muted = !Call.muted; tick++ }
            .semantics { contentDescription = if (Call.muted) "Unmute" else "Mute" }, contentAlignment = Alignment.Center) {
            LineIcon(if (Call.muted || Call.frozen) Icons2.MicOff else Icons2.Mic, Color.White, 28.dp)
        }
    }
    stock?.let { s -> StockSheet(s) { stock = null } }
}

// ---- tab 1: holdings, two lines a stock ----

@Composable
private fun HoldingsTab(data: JSONObject, mode: String, onStock: (String) -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    val snap = data.optJSONObject("snapshot") ?: JSONObject()
    val b = snap.optJSONObject("books")?.optJSONObject(mode) ?: JSONObject()
    var sort by remember { mutableStateOf("value") }
    var note by remember { mutableStateOf("") }
    val order: Comparator<JSONObject> = when (sort) {
        "pnl%" -> compareByDescending<JSONObject> { it.optDouble("pnl_pct") }
        "pnl" -> compareByDescending<JSONObject> { it.optDouble("pnl") }
        "today" -> compareByDescending<JSONObject> { it.dbl("today_pct") ?: -999.0 }
        "name" -> compareBy<JSONObject> { it.optString("symbol") }
        else -> compareByDescending<JSONObject> { it.optDouble("value") }
    }
    val rows = b.optJSONArray("holdings").objs().sortedWith(order)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        item {
            val nepse = data.optJSONObject("market")?.optJSONArray("indices").objs().firstOrNull { it.optString("name") == "NEPSE Index" }
            SummaryStrip(mode, b, nepse?.dbl("pct"))
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    listOf("value" to "Value", "pnl%" to "P/L %", "pnl" to "P/L Rs", "today" to "Today", "name" to "A-Z").forEach { (k, label) ->
                        Text(label, style = Type.small.copy(fontWeight = if (sort == k) Type.strong else null),
                            color = if (sort == k) c.bg else c.ink,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (sort == k) c.ink else c.field)
                                .clickable { sort = k }.padding(horizontal = Space.m, vertical = Space.xs))
                    }
                }
                if (mode == "live") Text("Fetch", style = Type.small.copy(fontWeight = Type.strong), color = c.blueInk,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable {
                        io(scope, { Trading.act(ctx, "fetch") }, { note = it.message ?: "failed" }) {
                            note = "Asked: MeroShare via your laptop, about a minute. You'll get a notification."; onChanged()
                        }
                    }.padding(Space.s))
            }
        }
        if (note.isNotEmpty()) item { Text(note, style = Type.small, color = c.muted) }
        if (rows.isEmpty()) item {
            Text(if (mode == "live") "No live holdings yet: tap Fetch (MeroShare)." else "The paper book is empty: sync it with live in Settings > Trading.",
                style = Type.body, color = c.muted, modifier = Modifier.padding(vertical = Space.l))
        }
        items(rows, key = { it.optString("symbol") }) { h -> HoldingRow(h) { onStock(h.optString("symbol")) } }
        item { Text("Prices as of ${rows.firstOrNull()?.optString("price_day") ?: "–"} · costs and tax included in P/L",
            style = Type.small, color = c.muted, modifier = Modifier.padding(top = Space.s)) }
    }
}

@Composable
private fun HoldingRow(h: JSONObject, onTap: () -> Unit) {
    val c = LocalMochi.current
    val pnlPct = h.dbl("pnl_pct")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).clickable(onClick = onTap)
        .padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(h.optString("symbol"), style = Type.body.copy(fontWeight = Type.strong), color = c.ink)
                Text("${h.optInt("qty")} · ${rs(h.optDouble("value"))}", style = Type.small, color = c.muted)
                h.optString("next_sellable").takeIf { it.isNotBlank() && it != "null" }?.let {
                    Badge("T+2 ${it.takeLast(5)}", c.amberBg, c.amberInk)
                }
                if (h.optBoolean("wacc_unknown")) Badge("WACC?", c.violetBg, c.violetInk)
            }
            val since = h.optInt("held_days", -1).takeIf { it >= 0 }?.let { " · ${it} d" } ?: ""
            Text("${"%.1f".format(h.optDouble("avg"))} → ${h.dbl("ltp")?.let { "%.1f".format(it) } ?: "–"} · " +
                "${rs(h.optDouble("pnl"))} · today ${pct(h.dbl("today_pct"))}$since",
                style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(pct(pnlPct), style = Type.body.copy(fontWeight = Type.strong), color = upDown(c, pnlPct))
            Spark(h.optJSONArray("spark"), Modifier.width(64.dp).height(20.dp))
        }
    }
}

@Composable
private fun Badge(text: String, bg: Color, ink: Color) {
    Text(text, style = Type.small, color = ink, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 6.dp))
}

@Composable
private fun Spark(arr: JSONArray?, modifier: Modifier) {
    val c = LocalMochi.current
    val v = (0 until (arr?.length() ?: 0)).map { arr!!.optDouble(it) }.filter { !it.isNaN() }
    if (v.size < 2) return
    val color = if (v.last() >= v.first()) c.greenInk else c.danger
    Canvas(modifier) {
        val lo = v.min(); val hi = v.max(); val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val p = Path()
        v.forEachIndexed { i, x ->
            val px = size.width * i / (v.size - 1); val py = size.height * (1 - ((x - lo) / span).toFloat())
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        drawPath(p, color, style = Stroke(width = 2.dp.toPx()))
    }
}

// ---- tab 2: watchlist (distance to each trigger) and Samir's open suggestions ----

@Composable
private fun WatchTab(data: JSONObject, onStock: (String) -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    val snap = data.optJSONObject("snapshot") ?: JSONObject()
    val watches = snap.optJSONArray("watches").objs().sortedBy { abs(it.dbl("away_pct") ?: 999.0) }
    val ideas = snap.optJSONArray("cards").objs().filter { it.optString("type") == "suggestion" }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (ideas.isNotEmpty()) {
            item { Text("Samir suggests", style = Type.title, color = c.ink) }
            items(ideas, key = { "s" + it.optInt("id") }) { TradeCard(it, onChanged) }
        }
        item { Text("Watching (${watches.size})", style = Type.title, color = c.ink, modifier = Modifier.padding(top = Space.s)) }
        if (watches.isEmpty()) item { Text("Nothing yet: approve one of Samir's suggestions, or ask him to watch a stock.", style = Type.body, color = c.muted) }
        items(watches, key = { "w" + it.optInt("id") }) { w ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).clickable { onStock(w.optString("symbol")) }
                .padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${w.optString("symbol")} · ${w.dbl("ltp")?.let { "%.1f".format(it) } ?: "–"}", style = Type.body.copy(fontWeight = Type.strong), color = c.ink)
                    Text("${w.optString("action")} ${w.optString("op")} ${w.optString("price")}" +
                        (if (w.optInt("stoploss") == 1) " · stop-loss" else "") + " · ${w.optString("why")}",
                        style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(w.dbl("away_pct")?.let { "%.1f%% away".format(abs(it)) } ?: "–", style = Type.small.copy(fontWeight = Type.strong),
                        color = if (abs(w.dbl("away_pct") ?: 99.0) < 2) c.amberInk else c.muted)
                    Text("Remove", style = Type.small, color = c.muted, modifier = Modifier.clickable {
                        io(scope, { Trading.act(ctx, "unwatch", "id" to w.optInt("id")) }) { onChanged() }
                    })
                }
            }
        }
    }
}

// ---- tab 3: market (indices, sector heat strip, breadth, movers) ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarketTab(data: JSONObject, onStock: (String) -> Unit) {
    val c = LocalMochi.current
    val m = data.optJSONObject("market") ?: return Text("No market data yet.", style = Type.body, color = c.muted, modifier = Modifier.padding(Space.xl))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        item {
            val st = m.optJSONObject("status")
            Text("${if (st?.optString("isOpen") == "OPEN") "Open now" else "Closed"} · day ${m.optString("day")}", style = Type.small, color = c.muted)
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                m.optJSONArray("indices").objs().forEach { i ->
                    Column(Modifier.clip(RoundedCornerShape(18.dp)).background(c.card).padding(Space.m)) {
                        Text(i.optString("name"), style = Type.small, color = c.muted)
                        Text("%.2f".format(i.optDouble("close")), style = Type.title, color = c.ink)
                        Text(pct(i.dbl("pct")), style = Type.small, color = upDown(c, i.dbl("pct")))
                    }
                }
            }
        }
        item {
            val br = m.optJSONObject("breadth") ?: JSONObject()
            val up = br.optInt("up"); val down = br.optInt("down"); val same = br.optInt("same"); val all = max(1, up + down + same)
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text("Breadth: $up up · $down down · $same same", style = Type.small, color = c.muted)
                Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                    if (up > 0) Box(Modifier.weight(up.toFloat() / all).fillMaxSize().background(c.greenInk))
                    if (same > 0) Box(Modifier.weight(same.toFloat() / all).fillMaxSize().background(c.line))
                    if (down > 0) Box(Modifier.weight(down.toFloat() / all).fillMaxSize().background(c.danger))
                }
            }
        }
        item {
          Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text("Sectors", style = Type.title, color = c.ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                m.optJSONArray("sectors").objs().forEach { s ->
                    val p = s.dbl("pct") ?: 0.0
                    Text("${s.optString("name").removeSuffix(" Index")} ${pct(p)}", style = Type.small,
                        color = if (p >= 0) c.greenInk else c.danger,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (p >= 0) c.greenBg else c.amberBg)
                            .padding(horizontal = Space.s, vertical = 2.dp))
                }
            }
          }
        }
        listOf("gainers" to "Top gainers", "losers" to "Top losers", "turnover" to "Top turnover").forEach { (k, label) ->
            item(k) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(label, style = Type.title, color = c.ink)
                    m.optJSONArray(k).objs().forEach { x ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onStock(x.optString("symbol")) }.padding(vertical = 4.dp)) {
                            Text(x.optString("symbol"), style = Type.body.copy(fontWeight = Type.strong), color = c.ink, modifier = Modifier.width(88.dp))
                            Text(x.optString("name"), style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(if (k == "turnover") rs(x.optDouble("turnover") / 1e6) + "M" else pct(x.dbl("percentageChange")),
                                style = Type.small, color = if (k == "turnover") c.ink else upDown(c, x.dbl("percentageChange")))
                        }
                    }
                }
            }
        }
    }
}

// ---- tab 4: journal (win rate, Paper vs Live vs NEPSE, lessons, decisions) ----

@Composable
private fun JournalTab(data: JSONObject, mode: String) {
    val c = LocalMochi.current
    val snap = data.optJSONObject("snapshot") ?: JSONObject()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                listOf("paper", "live").forEach { m ->
                    val st = snap.optJSONObject("stats")?.optJSONObject(m) ?: JSONObject()
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(c.card).padding(Space.m)) {
                        Text(m.uppercase(), style = Type.small.copy(fontWeight = Type.strong), color = if (m == "paper") c.blueInk else c.greenInk)
                        if (st.optInt("scored") == 0) Text("No scored decisions yet", style = Type.small, color = c.muted)
                        else {
                            Text("${st.optDouble("win_rate")}% right", style = Type.title, color = c.ink)
                            Text("${st.optInt("scored")} scored · avg ${pct(st.dbl("avg_return"))}", style = Type.small, color = c.muted)
                        }
                    }
                }
            }
        }
        item { EquityChart(snap.optJSONArray("equity")) }
        val lessons = snap.optJSONArray("lessons").objs()
        if (lessons.isNotEmpty()) item { Text("Lessons", style = Type.title, color = c.ink) }
        items(lessons, key = { "l" + it.optInt("id") }) { l ->
            val n = l.optInt("hits") + l.optInt("misses")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).padding(Space.m)) {
                Text(l.optString("text"), style = Type.body, color = c.ink)
                Text("${l.optString("status")} · ${l.optInt("hits")}/$n right · ${l.optString("tags")}", style = Type.small,
                    color = if (l.optString("status") == "trusted") c.greenInk else c.muted)
            }
        }
        val decisions = snap.optJSONArray("journal").objs().filter { it.optString("mode") == mode }
        item { Text("Decisions (${mode})", style = Type.title, color = c.ink) }
        if (decisions.isEmpty()) item { Text("None yet.", style = Type.body, color = c.muted) }
        items(decisions, key = { "d" + it.optInt("id") }) { d ->
            val o = d.optJSONObject("outcome")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).padding(Space.m)) {
                Row {
                    Text("${d.optString("side").uppercase()} ${d.optString("symbol")} @ ${d.optString("entry")}", style = Type.body.copy(fontWeight = Type.strong),
                        color = c.ink, modifier = Modifier.weight(1f))
                    Text(o?.let { pct(it.dbl("return_pct")) } ?: d.optString("status"), style = Type.small, color = upDown(c, o?.dbl("return_pct")))
                }
                Text(d.optString("why"), style = Type.small, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (d.optString("tags").isNotBlank()) Text(d.optString("tags"), style = Type.small, color = c.blueInk)
            }
        }
    }
}

/** Paper vs Live vs NEPSE, each as % from its first day (one point a day after the close). */
@Composable
private fun EquityChart(arr: JSONArray?) {
    val c = LocalMochi.current
    val rows = arr.objs()
    val days = rows.map { it.optString("day") }.distinct().sorted()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text("Paper vs Live vs NEPSE", style = Type.small.copy(fontWeight = Type.strong), color = c.ink)
        if (days.size < 2) { Text("Starts after two market days.", style = Type.small, color = c.muted); return@Column }
        fun series(sel: (JSONObject) -> Double?): List<Double?> {
            val byDay = rows.groupBy { it.optString("day") }
            val v = days.map { d -> byDay[d]?.firstNotNullOfOrNull(sel) }
            val base = v.firstOrNull { it != null && it != 0.0 } ?: return v.map { null }
            return v.map { x -> x?.let { (it - base) / base * 100 } }
        }
        val paper = series { if (it.optString("mode") == "paper") it.optString("value").toDoubleOrNull()?.plus(it.optString("collateral").toDoubleOrNull() ?: 0.0) else null }
        val live = series { if (it.optString("mode") == "live") it.optString("value").toDoubleOrNull()?.plus(it.optString("collateral").toDoubleOrNull() ?: 0.0) else null }
        val nepse = series { it.dbl("nepse") }
        val all = (paper + live + nepse).filterNotNull()
        val lo = min(0.0, all.minOrNull() ?: 0.0); val hi = max(0.0, all.maxOrNull() ?: 0.0); val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            fun line(s: List<Double?>, color: Color) {
                val p = Path(); var started = false
                s.forEachIndexed { i, x ->
                    if (x == null) return@forEachIndexed
                    val px = size.width * i / (s.size - 1); val py = size.height * (1 - ((x - lo) / span).toFloat())
                    if (!started) { p.moveTo(px, py); started = true } else p.lineTo(px, py)
                }
                drawPath(p, color, style = Stroke(width = 2.dp.toPx()))
            }
            val zero = size.height * (1 - ((0 - lo) / span).toFloat())
            drawLine(c.line, Offset(0f, zero), Offset(size.width, zero), 1.dp.toPx())
            line(nepse, c.muted); line(paper, c.blueInk); line(live, c.greenInk)
        }
        Text("blue paper · green live · grey NEPSE", style = Type.small, color = c.muted)
    }
}

// ---- tab 5: calendar (book closes, dividends, bonus/rights, AGMs) ----

@Composable
private fun CalendarTab(data: JSONObject, onStock: (String) -> Unit) {
    val c = LocalMochi.current
    val ev = data.optJSONObject("snapshot")?.optJSONArray("corporate").objs().sortedBy { it.optString("day") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        item { Text("Book closes, dividends, bonus and right shares for your stocks (from ShareSansar / Samir's reading)", style = Type.small, color = c.muted) }
        if (ev.isEmpty()) item { Text("Nothing coming up for your stocks.", style = Type.body, color = c.muted) }
        items(ev, key = { "e" + it.optInt("id") }) { e ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).clickable { onStock(e.optString("symbol")) }.padding(Space.m)) {
                Column(Modifier.width(92.dp)) {
                    Text(e.optString("day"), style = Type.small.copy(fontWeight = Type.strong), color = c.ink)
                    Text(e.optString("kind"), style = Type.small, color = c.amberInk)
                }
                Column(Modifier.weight(1f)) {
                    Text(e.optString("symbol"), style = Type.body.copy(fontWeight = Type.strong), color = c.ink)
                    Text(e.optString("detail"), style = Type.small, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---- one stock: candlestick chart, indicators, patterns, company, fundamentals, brokers, depth ----

@Composable
internal fun StockSheet(symbol: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf<JSONObject?>(null) }
    var days by remember { mutableIntStateOf(60) }
    LaunchedEffect(symbol) {
        repeat(12) {   // a stock the server hasn't prepared is ready ~10 s after it's asked for
            io(scope, { Trading.stock(ctx, symbol) }) { d = it }
            delay(if (d?.optBoolean("pending") == true || d == null) 3_000 else 60_000)
            if (d != null && d?.optBoolean("pending") != true) return@LaunchedEffect
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons2.Back, "Back", c.field, c.ink, 44.dp, onClose)
                val ind = d?.optJSONObject("indicators")
                Column(Modifier.weight(1f).padding(horizontal = Space.m)) {
                    Text(symbol, style = Type.title, color = c.ink)
                    Text(d?.optJSONObject("company")?.optString("name") ?: "", style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ind?.let { Text("%.1f".format(it.optDouble("close")), style = Type.title, color = c.ink) }
            }
            val x = d
            if (x == null || x.optBoolean("pending")) {
                Text("Getting $symbol's details…", style = Type.body, color = c.muted, modifier = Modifier.padding(Space.xl))
                return@Column
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    listOf(30, 60, 120, 250).forEach { n ->
                        Text("${n}d", style = Type.small, color = if (days == n) c.bg else c.ink,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (days == n) c.ink else c.field)
                                .clickable { days = n }.padding(horizontal = Space.m, vertical = 2.dp))
                    }
                }
                Candles(x.optJSONArray("bars").objs().takeLast(days), x.optJSONObject("indicators"))
                x.optJSONObject("indicators")?.let { IndicatorGrid(it) }
                val pats = x.optJSONArray("patterns").objs().takeLast(6).reversed()
                if (pats.isNotEmpty()) InfoBlock("Candle patterns") {
                    pats.forEach { p -> Text("${p.optString("day")}: ${p.optJSONArray("patterns")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }}",
                        style = Type.small, color = c.ink) }
                }
                x.optJSONObject("company")?.let { co ->
                    InfoBlock("Company") {
                        listOf("sector" to "Sector", "promoter_pct" to "Promoter %", "public_pct" to "Public %", "listed_shares" to "Listed shares",
                            "market_cap" to "Market cap", "credit_rating" to "Credit rating", "listed_on" to "Listed on").forEach { (k, label) ->
                            val v = co.opt(k)
                            if (v != null && v != JSONObject.NULL && v.toString().isNotBlank()) KeyValue(label, v.toString())
                        }
                    }
                }
                val f = x.optJSONArray("fundamentals").objs().filter { it.optString("field") != "checked" }
                if (f.isNotEmpty()) InfoBlock("Fundamentals") {
                    val latest = f.filter { it.optString("period") == "latest" || it.optString("field") == "eps" }
                    latest.forEach { r -> KeyValue(r.optString("field").replace('_', ' ') + (if (r.optString("period") != "latest") " (${r.optString("period")})" else ""), r.optString("value")) }
                    val divs = f.filter { it.optString("field") == "cash_dividend_pct" || it.optString("field") == "bonus_pct" }.take(8)
                    if (divs.isNotEmpty()) Text("Dividends: " + divs.joinToString(" · ") { "${it.optString("period").removePrefix("FY")} ${if (it.optString("field") == "bonus_pct") "bonus" else "cash"} ${it.optString("value")}%" },
                        style = Type.small, color = c.muted)
                    Text("Sources: Merolagani, ShareSansar (public pages)", style = Type.small, color = c.muted)
                }
                x.optJSONObject("brokers")?.let { br ->
                    InfoBlock("Today's top brokers (${br.optInt("trades")} trades)") {
                        Row {
                            Column(Modifier.weight(1f)) { Text("Buying", style = Type.small, color = c.greenInk)
                                br.optJSONArray("buyers")?.let { a -> (0 until a.length()).forEach { i -> val p = a.optJSONArray(i); Text("#${p?.opt(0)} · ${p?.opt(1)}", style = Type.small, color = c.ink) } } }
                            Column(Modifier.weight(1f)) { Text("Selling", style = Type.small, color = c.danger)
                                br.optJSONArray("sellers")?.let { a -> (0 until a.length()).forEach { i -> val p = a.optJSONArray(i); Text("#${p?.opt(0)} · ${p?.opt(1)}", style = Type.small, color = c.ink) } } }
                        }
                    }
                }
                x.optString("note").takeIf { it.isNotBlank() && it != "null" }?.let { Text(it, style = Type.small, color = c.muted) }
                Spacer(Modifier.height(Space.xxl))
            }
        }
    }
}

@Composable
private fun InfoBlock(title: String, content: @Composable () -> Unit) {
    val c = LocalMochi.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).padding(Space.m), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = Type.small.copy(fontWeight = Type.strong), color = c.ink)
        content()
    }
}

@Composable
private fun KeyValue(k: String, v: String) {
    val c = LocalMochi.current
    Row(Modifier.fillMaxWidth()) {
        Text(k, style = Type.small, color = c.muted, modifier = Modifier.weight(1f))
        Text(v, style = Type.small, color = c.ink, textAlign = TextAlign.End)
    }
}

@Composable
private fun IndicatorGrid(i: JSONObject) {
    val c = LocalMochi.current
    InfoBlock("Indicators (${i.optString("day")})") {
        val rsi = i.dbl("rsi14")
        KeyValue("Trend", i.optString("trend"))
        KeyValue("RSI 14", rsi?.let { "%.1f".format(it) + when { it >= 70 -> " (overbought)"; it <= 30 -> " (oversold)"; else -> "" } } ?: "–")
        KeyValue("MACD / signal", "${i.dbl("macd") ?: "–"} / ${i.dbl("macd_signal") ?: "–"}")
        KeyValue("SMA 20 / 50 / 200", "${i.dbl("sma20") ?: "–"} / ${i.dbl("sma50") ?: "–"} / ${i.dbl("sma200") ?: "–"}")
        KeyValue("Bollinger", "${i.dbl("bollinger_low") ?: "–"} – ${i.dbl("bollinger_high") ?: "–"}")
        KeyValue("ATR 14", "${i.dbl("atr14") ?: "–"}")
        KeyValue("Volume vs 20-day avg", i.dbl("volume_vs_avg20")?.let { "%.2f×".format(it) } ?: "–")
        KeyValue("52 weeks", "${i.dbl("wk52_low") ?: "–"} – ${i.dbl("wk52_high") ?: "–"} (${i.dbl("pct_below_52w_high")?.let { "%.1f%% below high".format(it) } ?: ""})")
    }
}

/** Candlesticks drawn here (no chart library: the app stays small), with SMA 20 and Bollinger bands from the server. */
@Composable
private fun Candles(bars: List<JSONObject>, ind: JSONObject?) {
    val c = LocalMochi.current
    if (bars.size < 2) { Text("Not enough days yet.", style = Type.small, color = c.muted); return }
    val highs = bars.map { it.optDouble("high") }.filter { !it.isNaN() }
    val lows = bars.map { it.optDouble("low") }.filter { !it.isNaN() }
    val lo = lows.minOrNull() ?: 0.0; val hi = highs.maxOrNull() ?: 1.0; val span = (hi - lo).takeIf { it > 0 } ?: 1.0
    val closes = bars.map { it.optDouble("close") }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).padding(Space.s)) {
        Canvas(Modifier.fillMaxWidth().height(220.dp)) {
            val w = size.width / bars.size
            fun y(v: Double) = size.height * (1 - ((v - lo) / span).toFloat())
            bars.forEachIndexed { i, b ->
                val o = b.optDouble("open").takeIf { !it.isNaN() } ?: b.optDouble("close")
                val cl = b.optDouble("close"); val h = b.optDouble("high"); val l = b.optDouble("low")
                if (cl.isNaN() || h.isNaN() || l.isNaN()) return@forEachIndexed
                val color = if (cl >= o) c.greenInk else c.danger
                val cx = w * i + w / 2
                drawLine(color, Offset(cx, y(h)), Offset(cx, y(l)), max(1f, w * 0.12f))
                val top = y(max(o, cl)); val bottom = y(min(o, cl))
                drawRect(color, Offset(cx - w * 0.35f, top), Size(w * 0.7f, max(1.5f, bottom - top)))
            }
            // 20-day average over the shown days
            if (closes.size >= 20) {
                val p = Path(); var started = false
                for (i in 19 until closes.size) {
                    val avg = closes.subList(i - 19, i + 1).average()
                    val px = w * i + w / 2
                    if (!started) { p.moveTo(px, y(avg)); started = true } else p.lineTo(px, y(avg))
                }
                drawPath(p, c.blueInk, style = Stroke(width = 1.5.dp.toPx()))
            }
        }
        Text("${bars.first().optString("day")} – ${bars.last().optString("day")} · blue: 20-day average · high ${"%.1f".format(hi)} low ${"%.1f".format(lo)}",
            style = Type.small, color = c.muted)
    }
}

// ---- Settings > Trading ----

@Composable
internal fun TradingSettings() {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf<JSONObject?>(null) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { io(scope, { Trading.settings(ctx) }) { s = it } }
    val cur = s ?: return Text("Loading…", style = Type.small, color = c.muted)
    var mode by remember(cur) { mutableStateOf(cur.optString("mode", "paper")) }
    var auto by remember(cur) { mutableStateOf(cur.optBoolean("auto_trade", false)) }
    val fields = listOf("max_per_trade" to "Max money per trade (Rs)", "max_pct_one_stock" to "Max % of the book in one stock",
        "daily_loss_limit" to "Daily loss limit (Rs)", "weekly_loss_limit" to "Weekly loss limit (Rs)",
        "cgt_short" to "Capital gains tax ≤ 1 year (%)", "cgt_long" to "Capital gains tax > 1 year (%)")
    val defaults = mapOf("max_per_trade" to "50000", "max_pct_one_stock" to "25", "daily_loss_limit" to "10000",
        "weekly_loss_limit" to "25000", "cgt_short" to "5", "cgt_long" to "3.75")
    val values = remember(cur) { fields.associate { (k, _) -> k to mutableStateOf(if (cur.has(k)) cur.optString(k).removeSuffix(".0") else defaults[k]!!) } }
    var holidays by remember(cur) { mutableStateOf(cur.optJSONArray("holidays")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } } ?: "") }
    var collateral by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text("Book Samir works on", style = Type.body, color = c.ink)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.field).padding(4.dp)) {
            listOf("paper" to "Paper", "live" to "Live").forEach { (k, label) ->
                val on = mode == k
                Text(label, style = Type.body.copy(fontWeight = if (on) Type.strong else null), color = if (on) c.bg else c.ink,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                        .background(if (on) c.ink else c.field).clickable { mode = k }.padding(vertical = Space.m))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto trade", style = Type.body, color = c.ink)
                Text("Off: you place every order in TMS. On needs the server's own switch too (and SEBON's rules), so this alone never trades.",
                    style = Type.small, color = c.muted)
            }
            Switch(auto, { auto = it }, colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.bg))
        }
        fields.forEach { (k, label) ->
            Text(label, style = Type.small, color = c.muted)
            app.mochi.phone.ui.Field(values[k]!!.value, { values[k]!!.value = it }, label, singleLine = true)
        }
        Text("NEPSE holidays (YYYY-MM-DD, comma-separated): no trading, T+2 skips them", style = Type.small, color = c.muted)
        app.mochi.phone.ui.Field(holidays, { holidays = it }, "2026-11-12, 2026-12-25", singleLine = false)
        Pill("Save trading settings", true, Modifier.fillMaxWidth()) {
            val out = JSONObject().put("mode", mode).put("auto_trade", auto)
            fields.forEach { (k, _) -> values[k]!!.value.toDoubleOrNull()?.let { out.put(k, it) } }
            out.put("holidays", JSONArray(holidays.split(',', ' ', '\n').map { it.trim() }.filter { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) }))
            io(scope, { Trading.saveSettings(ctx, out) }, { note = it.message ?: "failed" }) { note = "Saved."; s = it.optJSONObject("settings") }
        }
        Text("Available Trading Limit (from TMS): TMS can't be read automatically", style = Type.small, color = c.muted)
        app.mochi.phone.ui.Field(collateral, { collateral = it }, "e.g. 85000", singleLine = true) {
            Pill("Set", true, height = 36.dp) {
                collateral.toDoubleOrNull()?.let { v -> io(scope, { Trading.act(ctx, "collateral", "amount" to v, "mode" to "live") }, { note = it.message ?: "failed" }) { note = "Collateral set."; collateral = "" } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Pill("Fetch holdings", false, Modifier.weight(1f)) {
                io(scope, { Trading.act(ctx, "fetch") }, { note = it.message ?: "failed" }) { note = "Asked: MeroShare via your laptop; you'll get a notification." }
            }
            Pill("Sync paper ← live", false, Modifier.weight(1f)) {
                io(scope, { Trading.act(ctx, "sync-paper") }, { note = it.message ?: "failed" }) { note = "Paper book = your live book now." }
            }
        }
        if (note.isNotEmpty()) Text(note, style = Type.small, color = c.muted)
        MeroShareAccount()
    }
}

/** MeroShare login, sent with this phone's vault key and fingerprint to the server's root-only store (never to Samir). */
@Composable
private fun MeroShareAccount() {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val a = ctx as? Activity ?: return
    var dp by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    Text("MeroShare account", style = Type.body, color = c.ink, modifier = Modifier.padding(top = Space.s))
    Text("Stored only on the server, in the vault, readable only by the holdings helper. Needs this phone set up for Unlock (Security).",
        style = Type.small, color = c.muted)
    app.mochi.phone.ui.Field(dp, { dp = it }, "DP code, e.g. 13700", singleLine = true)
    app.mochi.phone.ui.Field(user, { user = it }, "MeroShare username", singleLine = true)
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.field).padding(Space.l)) {
        if (pass.isEmpty()) Text("MeroShare password", style = Type.body, color = c.muted)
        androidx.compose.foundation.text.BasicTextField(pass, { pass = it }, singleLine = true, textStyle = Type.body.copy(color = c.ink),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.ink),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "MeroShare password" })
    }
    Pill("Save account (fingerprint)", true, Modifier.fillMaxWidth()) {
        if (dp.isBlank() || user.isBlank() || pass.isBlank()) { note = "Fill all three."; return@Pill }
        VaultUnlock.send(a, "Save the MeroShare account", "/trading/creds",
            JSONObject().put("meroshare", JSONObject().put("dp", dp.trim()).put("username", user.trim()).put("password", pass))) { err ->
            note = err ?: "Saved on the server."
            if (err == null) { pass = ""; user = ""; dp = "" }
        }
    }
    if (note.isNotEmpty()) Text(note, style = Type.small, color = c.muted)
}
