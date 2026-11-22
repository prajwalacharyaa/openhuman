package app.mochi.phone

import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.sqrt
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import app.mochi.phone.ui.MuteFabIdle
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
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.selection.selectable
import app.mochi.phone.ui.Refreshable

/* Portfolio & market: the tabs (holdings, trades, IPOs, watchlist, market, journal, calendar). Split out of Trading.kt 2026-11-22. */

// ---- tab 1: holdings, two lines a stock ----

@Composable
internal fun HoldingsTab(data: JSONObject, mode: String, onStock: (String) -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    val snap = data.optJSONObject("snapshot") ?: JSONObject()
    val b = snap.optJSONObject("books")?.optJSONObject(mode) ?: JSONObject()
    var sort by remember { mutableStateOf("value") }
    var desc by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    val base: Comparator<JSONObject> = when (sort) {
        "pnl%" -> compareBy<JSONObject> { it.optDouble("pnl_pct") }
        "pnl" -> compareBy<JSONObject> { it.optDouble("pnl") }
        "today" -> compareBy<JSONObject> { it.dbl("today_pct") ?: -999.0 }
        "name" -> compareBy<JSONObject> { it.optString("symbol") }
        else -> compareBy<JSONObject> { it.optDouble("value") }
    }
    val order: Comparator<JSONObject> = if (desc) base.reversed() else base
    val rows = b.optJSONArray("holdings").objs().sortedWith(order)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        item {
            val nepse = data.optJSONObject("market")?.optJSONArray("indices").objs().firstOrNull { it.optString("name") == "NEPSE Index" }
            SummaryStrip(mode, b, nepse?.dbl("pct"), snap.optJSONObject("money")?.optJSONObject(mode),
                snap.optJSONObject("settings")?.optJSONArray("holidays"))
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    listOf("value" to "Value", "pnl%" to "P/L %", "pnl" to "P/L Rs", "today" to "Today", "name" to "A-Z").forEach { (k, label) ->
                        Text(label + (if (sort == k) (if (desc) "  ↓" else "  ↑") else ""), style = Type.small.copy(fontWeight = if (sort == k) Type.strong else null),
                            color = if (sort == k) c.bg else c.ink,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (sort == k) c.ink else c.field)
                                .selectable(selected = sort == k, role = Role.RadioButton) { if (sort == k) desc = !desc else { sort = k; desc = k != "name" } }
                                .semantics { contentDescription = "Sort by $label" + if (sort == k) (if (desc) ", high to low" else ", low to high") else "" }
                                .padding(horizontal = Space.m, vertical = Space.xs))
                    }
                }
                if (mode == "live") Text("Fetch", style = Type.small.copy(fontWeight = Type.strong), color = c.blueInk,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) {
                        io(scope, { Trading.act(ctx, "fetch") }, { note = it.message ?: "failed" }) {
                            note = "Asked MeroShare: under a minute. You'll get a notification."; onChanged()
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HoldingRow(h: JSONObject, onTap: () -> Unit) {
    val c = LocalMochi.current
    val pnl = h.optDouble("pnl", 0.0)
    val pnlPct = h.dbl("pnl_pct")
    val since = h.optString("since").takeIf { it.isNotBlank() && it != "null" }?.let {
        runCatching { java.time.LocalDate.parse(it).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")) }.getOrDefault(it)
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).clickable(role = Role.Button, onClick = onTap).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.m)) {
        // name, how many, since when · the change and the little graph
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(h.optString("symbol"), style = Type.title, color = c.ink)
                Text("${h.optInt("qty")} shares" + (since?.let { " · since $it" } ?: ""), style = Type.small, color = c.muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (pnlPct != null && pnlPct != 0.0) TrendArrow(pnlPct > 0, upDown(c, pnlPct), 12.dp)
                    Text(pct(pnlPct), style = Type.title, color = upDown(c, pnlPct))
                }
                Spark(h.optJSONArray("spark"), Modifier.padding(top = 2.dp).width(72.dp).height(22.dp))
            }
        }
        // the figures, each with its name
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.field).padding(horizontal = Space.m, vertical = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            MiniStat("Bought at (avg)", "%.2f".format(h.optDouble("avg")), Modifier.weight(1f))
            MiniStat("Price now", h.dbl("ltp")?.let { "%.2f".format(it) } ?: "–", Modifier.weight(1f),
                ink = h.dbl("today_pct")?.let { upDown(c, it) })
            MiniStat("Profit / loss", (if (pnl > 0) "+" else "") + rs(pnl), Modifier.weight(1f), upDown(c, pnl), end = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniStat("Invested", rs(h.optDouble("cost", h.optDouble("avg") * h.optInt("qty"))), Modifier.weight(1f))
            MiniStat("Worth now", rs(h.optDouble("value")), Modifier.weight(1f))
            MiniStat("Today", pct(h.dbl("today_pct")), Modifier.weight(1f), upDown(c, h.dbl("today_pct")), end = true)
        }
        // badges wrap onto more lines when a stock has many (UAIL: merger + bonus + rights): the card just grows
        val names = mapOf("ipo" to "IPO", "secondary" to "Bought", "bonus" to "Bonus", "rights" to "Rights", "merger" to "Merger", "fpo" to "FPO")
        val origin = h.optJSONObject("origin")
        val verdict = h.optJSONObject("verdict")?.optString("verdict")
        val t2 = h.optString("next_sellable").takeIf { it.isNotBlank() && it != "null" }
        // bottom: where the shares came from (left) · what selling all of them today really brings (right)
        val sold = h.optJSONObject("if_sold")
        Row(verticalAlignment = Alignment.Bottom) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                origin?.let { o -> o.keys().asSequence().toList().forEach { k -> Badge("${names[k] ?: k} ${o.optInt(k)}", c.field, c.muted) } }
                t2?.let { Badge("Sellable ${it.takeLast(5)}", c.amberBg, c.amberInk) }
                if (h.optBoolean("wacc_unknown")) Badge("Buy price unknown", c.violetBg, c.violetInk)
                verdict?.let { v ->
                    Badge("Samir: $v", when (v) { "sell" -> c.amberBg; "add" -> c.greenBg; else -> c.field }, when (v) { "sell" -> c.danger; "add" -> c.greenInk; else -> c.ink })
                }
            }
            sold?.let {
                MiniStat("If sold today", rs(it.optDouble("net")), Modifier.padding(start = Space.s), end = true)
            }
        }
    }
}

@Composable
internal fun Badge(text: String, bg: Color, ink: Color) {
    Text(text, style = Type.small, color = ink, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 6.dp))
}

@Composable
internal fun Spark(arr: JSONArray?, modifier: Modifier) {
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

// ---- tab: trades, every fill as it was recorded (real = you placed it in TMS and confirmed; paper = pretend) ----

@Composable
internal fun TradesTab(data: JSONObject, mode: String, onStock: (String) -> Unit) {
    val c = LocalMochi.current
    val trades = data.optJSONObject("snapshot")?.optJSONArray("trades").objs().filter { it.optString("mode") == mode }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        item {
            Text(if (mode == "live") "Real trades you confirmed (\"I sold / bought it in TMS\"). MeroShare's Transaction History will be checked against these."
                 else "PAPER trades: pretend money, nothing real.", style = Type.small, color = c.muted)
        }
        if (trades.isEmpty()) item { Text("No ${mode} trades yet.", style = Type.body, color = c.muted) }
        items(trades, key = { "t" + it.optInt("id") }) { t ->
            val pnl = t.optString("pnl").toDoubleOrNull() ?: 0.0
            val sell = t.optString("side") == "sell"
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).clickable(role = Role.Button) { onStock(t.optString("symbol")) }
                .padding(Space.m), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${if (sell) "SOLD" else "BOUGHT"} ${t.optInt("qty")} ${t.optString("symbol")} @ ${t.optString("price")}",
                        style = Type.body.copy(fontWeight = Type.strong), color = if (sell) c.danger else c.greenInk)
                    if (t.optString("meroshare").isNotBlank()) Text("✓ in MeroShare ${t.optString("meroshare")}", style = Type.small, color = c.greenInk)
                    else if (t.optString("mode") == "live") Text("not in MeroShare yet (it shows after settlement)", style = Type.small, color = c.muted)
                    val amount = (t.optString("price").toDoubleOrNull() ?: 0.0) * t.optInt("qty")
                    Text("${t.optString("at").take(16).replace('T', ' ')} · ${rs(amount)} · fees ${rs(t.optString("fees").toDoubleOrNull() ?: 0.0, true)}" +
                        (if (sell) " · tax ${rs(t.optString("tax").toDoubleOrNull() ?: 0.0, true)}" else ""), style = Type.small, color = c.muted)
                }
                if (sell) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TrendArrow(pnl >= 0, upDown(c, pnl), 11.dp)
                    Text(rs(abs(pnl)).removePrefix("Rs "), style = Type.small.copy(fontWeight = Type.strong), color = upDown(c, pnl))
                }
            }
        }
    }
}

// ---- tab: IPOs, open now (apply) and every application with its result (from MeroShare) ----

@Composable
internal fun IpoTab(data: JSONObject, onChanged: () -> Unit) {
    val c = LocalMochi.current
    val snap = data.optJSONObject("snapshot")
    val open = snap?.optJSONArray("cards").objs().filter { it.optString("type") == "ipo" }
    val ipo = snap?.optJSONObject("ipo")
    val applied = ipo?.optJSONArray("applied").objs()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        item { Text("Open now" + (ipo?.optString("at")?.takeIf { it.isNotBlank() && it != "null" }?.take(16)?.replace('T', ' ')?.let { " (checked $it)" } ?: ""), style = Type.body.copy(fontWeight = Type.strong), color = c.ink) }
        if (open.isEmpty()) item { Text("No IPO open for you right now. The daily check runs at 16:10.", style = Type.small, color = c.muted) }
        items(open, key = { "o" + it.optString("id") }) { TradeCard(it, onChanged) }
        item { Text("Your applications", style = Type.body.copy(fontWeight = Type.strong), color = c.ink, modifier = Modifier.padding(top = Space.m)) }
        items(applied, key = { "a" + it.optString("applicantFormId") + it.optString("scrip") }) { a ->
            val d = a.optJSONObject("detail") ?: JSONObject()
            val st = d.optString("statusName").ifBlank { a.optString("statusName") }
            val color = when { st.startsWith("Alloted", true) -> c.greenInk; st.contains("Rejected", true) -> c.danger; else -> c.muted }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).padding(Space.m)) {
                Text("${a.optString("scrip")} · ${a.optString("shareTypeName")}: $st", style = Type.body.copy(fontWeight = Type.strong), color = color)
                Text("Applied ${d.optInt("appliedKitta")} on ${d.optString("appliedDate").take(10)}" +
                    (if (d.has("receivedKitta")) " · got ${d.optInt("receivedKitta")}" else "") +
                    (if (d.has("amount")) " · ${rs(d.optDouble("amount"))}" else ""), style = Type.small, color = c.muted)
                if (d.optString("meroshareRemark").isNotBlank()) Text(d.optString("meroshareRemark"), style = Type.small, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---- tab 2: watchlist (distance to each trigger) and Samir's open suggestions ----

@Composable
internal fun WatchTab(data: JSONObject, onStock: (String) -> Unit, onChanged: () -> Unit) {
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
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).clickable(role = Role.Button) { onStock(w.optString("symbol")) }
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
                    Text("Remove", style = Type.small, color = c.muted, modifier = Modifier.clickable(role = Role.Button) {
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
internal fun MarketTab(data: JSONObject, onStock: (String) -> Unit) {
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
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onStock(x.optString("symbol")) }.padding(vertical = 4.dp)) {
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
internal fun JournalTab(data: JSONObject, mode: String) {
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
internal fun EquityChart(arr: JSONArray?) {
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
internal fun CalendarTab(data: JSONObject, onStock: (String) -> Unit) {
    val c = LocalMochi.current
    val ev = data.optJSONObject("snapshot")?.optJSONArray("corporate").objs().sortedBy { it.optString("day") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        item { Text("Book closes, dividends, bonus and right shares for your stocks (from ShareSansar / Samir's reading)", style = Type.small, color = c.muted) }
        if (ev.isEmpty()) item { Text("Nothing coming up for your stocks.", style = Type.body, color = c.muted) }
        items(ev, key = { "e" + it.optInt("id") }) { e ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).clickable(role = Role.Button) { onStock(e.optString("symbol")) }.padding(Space.m)) {
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
