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

/* Portfolio & market: one stock's sheet, its panels and price chart, trading settings and the chart popup. Split out of Trading.kt 2026-11-22. */

// ---- one stock: candlestick chart, indicators, patterns, company, fundamentals, brokers, depth ----

@Composable
internal fun StockSheet(symbol: String, holding: JSONObject? = null, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(symbol) {
        repeat(12) {   // a stock the server hasn't prepared is ready ~10 s after it's asked for
            io(scope, { Trading.stock(ctx, symbol) }) { d = it }
            delay(if (d?.optBoolean("pending") == true || d == null) 3_000 else 60_000)
            if (d != null && d?.optBoolean("pending") != true) return@LaunchedEffect
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding()) {
            val x = d
            val bars = x?.optJSONArray("bars").objs()
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons2.Back, "Back", c.field, c.ink, 44.dp, onClose)
                Column(Modifier.weight(1f).padding(horizontal = Space.m)) {
                    Text(symbol, style = Type.title, color = c.ink)
                    Text(x?.optJSONObject("company")?.optString("name") ?: "", style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (bars.size >= 2) Column(horizontalAlignment = Alignment.End) {
                    val last = bars.last().optDouble("close"); val prev = bars[bars.size - 2].optDouble("close")
                    Text("%.1f".format(last), style = Type.title, color = c.ink)
                    Text(pct((last - prev) / prev * 100), style = Type.small, color = upDown(c, last - prev))
                }
            }
            if (x == null || x.optBoolean("pending")) {
                Text("Getting $symbol's details…", style = Type.body, color = c.muted, modifier = Modifier.padding(Space.xl))
                return@Column
            }
            val pos = x.optJSONObject("position")
            val mine = holding ?: pos?.optJSONObject("live") ?: pos?.optJSONObject("paper")
            val stop = (pos?.optJSONObject("live") ?: pos?.optJSONObject("paper"))?.optString("stop")?.toDoubleOrNull()
            val target = (pos?.optJSONObject("live") ?: pos?.optJSONObject("paper"))?.optString("target")?.toDoubleOrNull()
            val ind = x.optJSONObject("indicators")
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.m)) {
                PriceChart(bars, ind, mine?.dbl("avg"), stop, target)
                if (mine != null) PositionPanel(mine, holding, ind?.dbl("close"), stop, target)
                VerdictPanel(mine?.optJSONObject("verdict") ?: pos?.optJSONObject("live")?.optJSONObject("verdict"))
                SignalsPanel(x)
                x.optJSONObject("stats")?.let { PerformancePanel(it) }
                ind?.let { IndicatorsPanel(it) }
                FundamentalsPanel(x.optJSONArray("fundamentals").objs())
                x.optJSONObject("company")?.let { co ->
                    val rows = listOf("sector" to "Sector", "listed_shares" to "Listed shares", "market_cap" to "Market cap",
                        "promoter_pct" to "Promoter %", "public_pct" to "Public %", "credit_rating" to "Credit rating", "listed_on" to "Listed on")
                        .mapNotNull { (k, label) -> co.opt(k)?.takeIf { it != JSONObject.NULL && it.toString().isNotBlank() }?.let { label to it.toString() } }
                    if (rows.isNotEmpty()) Panel("Company") { rows.forEach { (k, v) -> KeyValue(k, v) } }
                }
                val pats = x.optJSONArray("patterns").objs().takeLast(5).reversed()
                if (pats.isNotEmpty()) Panel("Candle patterns") {
                    pats.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.optString("day").takeLast(5), style = Type.small, color = c.muted, modifier = Modifier.width(52.dp))
                            Text(p.optJSONArray("patterns")?.let { a -> (0 until a.length()).joinToString(" · ") { a.optString(it).replace('_', ' ') } } ?: "",
                                style = Type.small, color = c.ink)
                        }
                    }
                }
                x.optJSONObject("brokers")?.let { br ->
                    Panel("Today's top brokers", "${br.optInt("trades")} trades") {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                            listOf("buyers" to "Buying", "sellers" to "Selling").forEach { (k, label) ->
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(label, style = Type.small.copy(fontWeight = Type.strong), color = if (k == "buyers") c.greenInk else c.danger)
                                    br.optJSONArray(k)?.let { a -> (0 until a.length()).forEach { i -> val q = a.optJSONArray(i)
                                        KeyValue("Broker ${q?.opt(0)}", "${q?.opt(1)}") } }
                                }
                            }
                        }
                    }
                }
                x.optString("note").takeIf { it.isNotBlank() && it != "null" }?.let { Text(it, style = Type.small, color = c.muted) }
                Spacer(Modifier.height(Space.xxl))
            }
        }
    }
}

// ---- the stock page's building blocks ----

@Composable
internal fun Panel(title: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalMochi.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Type.title, color = c.ink, modifier = Modifier.weight(1f))
            trailing?.let { Text(it, style = Type.small, color = c.muted) }
        }
        content()
    }
}

/** Small labelled numbers in a grid (3 a row), the way a stock app shows them. */
@Composable
internal fun TileGrid(items: List<Triple<String, String, Color?>>, cols: Int = 3) {
    val c = LocalMochi.current
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        items.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                row.forEach { (label, value, color) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(c.field).padding(horizontal = Space.m, vertical = Space.s)) {
                        Text(label, style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(value, style = Type.body.copy(fontWeight = Type.strong), color = color ?: c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

internal fun num1(v: Double?) = v?.let { "%.1f".format(it) } ?: "–"

@Composable
internal fun PositionPanel(mine: JSONObject, holding: JSONObject?, ltp: Double?, stop: Double?, target: Double?) {
    val c = LocalMochi.current
    val qty = mine.optInt("qty"); val avg = mine.optDouble("avg")
    val pnl = holding?.dbl("pnl") ?: ltp?.let { (it - avg) * qty }
    val sold = holding?.optJSONObject("if_sold")
    Panel("Your position", holding?.optString("since")?.takeIf { it.isNotBlank() && it != "null" }?.let { "since $it" }) {
        TileGrid(listOfNotNull(
            Triple("Shares", "$qty", null),
            Triple("Avg cost", num1(avg), null),
            Triple("Profit / loss", pnl?.let { (if (it > 0) "+" else "") + rs(it) } ?: "–", pnl?.let { upDown(c, it) }),
            sold?.let { Triple("If sold today", rs(it.optDouble("net")), null) },
            sold?.let { Triple("Tax if sold", rs(it.optDouble("tax"), true), null) },
            sold?.let { Triple("Real profit", (if (it.optDouble("pnl") > 0) "+" else "") + rs(it.optDouble("pnl")), upDown(c, it.optDouble("pnl"))) },
            Triple("Stop-loss", stop?.let { num1(it) } ?: "none", if (stop == null) c.muted else c.danger),
            Triple("Target", target?.let { num1(it) } ?: "none", if (target == null) c.muted else c.greenInk),
        ))
        // each batch with its date: over a year is taxed 5%, under 7.5%
        val lots = holding?.optJSONArray("lots").objs().filter { it.optString("since").let { d -> d.isNotBlank() && d != "null" } }
        if (lots.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lots.forEach { l ->
                val days = l.optInt("days")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${l.optInt("qty")} ${l.optString("kind").takeIf { it.isNotBlank() && it != "null" }?.let { if (it == "secondary") "bought" else it } ?: ""}",
                        style = Type.small.copy(fontWeight = Type.strong), color = c.ink, modifier = Modifier.width(96.dp))
                    Text("${l.optString("since")} · $days days", style = Type.small, color = c.muted, modifier = Modifier.weight(1f))
                    Badge(if (days > 365) "5% tax" else "7.5% tax", if (days > 365) c.greenBg else c.field, if (days > 365) c.greenInk else c.muted)
                }
            }
        }
    }
}

@Composable
internal fun VerdictPanel(v: JSONObject?) {
    val c = LocalMochi.current
    Panel("Samir's view", v?.optString("at")?.take(10)) {
        if (v == null) Text("No view yet. Ask Samir \"review my holdings\".", style = Type.small, color = c.muted)
        else {
            val word = v.optString("verdict")
            Text(word.uppercase(), style = Type.title, color = when (word) { "sell" -> c.danger; "add" -> c.greenInk; else -> c.ink })
            Text(v.optString("why"), style = Type.small, color = c.ink)
        }
    }
}

@Composable
internal fun SignalsPanel(x: JSONObject) {
    val c = LocalMochi.current
    val sig = x.optJSONArray("signals").objs().sortedBy { when (it.optInt("side")) { 1 -> 0; -1 -> 1; else -> 2 } }
    if (sig.isEmpty()) return
    val up = sig.count { it.optInt("side") > 0 }; val down = sig.count { it.optInt("side") < 0 }; val flat = sig.size - up - down
    Panel("Signals", "$up up · $down down") {
        Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
            if (up > 0) Box(Modifier.weight(up.toFloat()).fillMaxHeight().background(c.greenInk))
            if (flat > 0) Box(Modifier.weight(flat.toFloat()).fillMaxHeight().background(c.field))
            if (down > 0) Box(Modifier.weight(down.toFloat()).fillMaxHeight().background(c.danger))
        }
        sig.forEach { g ->
            val side = g.optInt("side")
            val ink = if (side > 0) c.greenInk else if (side < 0) c.danger else c.muted
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Box(Modifier.width(14.dp).padding(top = 3.dp)) {
                    if (side != 0) TrendArrow(side > 0, ink, 12.dp) else Box(Modifier.size(6.dp).clip(CircleShape).background(ink))
                }
                Column(Modifier.weight(1f)) {
                    Text(g.optString("name"), style = Type.small.copy(fontWeight = Type.strong), color = c.ink)
                    Text(g.optString("text"), style = Type.small, color = c.muted)
                }
            }
        }
        Text("Calculated from prices and reports: facts, not advice.", style = Type.small, color = c.muted)
    }
}

@Composable
internal fun PerformancePanel(st: JSONObject) {
    val c = LocalMochi.current
    fun t(label: String, k: String) = Triple(label, pct(st.dbl(k)), upDown(c, st.dbl(k)))
    Panel("Performance") {
        TileGrid(listOf(t("1 week", "ret_1w"), t("1 month", "ret_1m"), t("3 months", "ret_3m"), t("1 year", "ret_1y"),
            Triple("Biggest drop", st.dbl("max_drop_pct")?.let { "%.1f%%".format(it) } ?: "–", c.danger),
            Triple("Daily move", st.dbl("daily_volatility_pct")?.let { "±%.1f%%".format(it) } ?: "–", null)))
    }
}

@Composable
internal fun IndicatorsPanel(i: JSONObject) {
    val c = LocalMochi.current
    val rsi = i.dbl("rsi14"); val macd = i.dbl("macd"); val sig = i.dbl("macd_signal")
    Panel("Indicators", i.optString("day").takeIf { it.isNotBlank() && it != "null" }) {
        TileGrid(listOf(
            Triple("RSI 14", rsi?.let { "%.0f".format(it) + when { it >= 70 -> " high"; it <= 30 -> " low"; else -> "" } } ?: "–",
                rsi?.let { if (it >= 70) c.danger else if (it <= 30) c.greenInk else null }),
            Triple("MACD", if (macd != null && sig != null) (if (macd > sig) "rising" else "falling") else "–",
                if (macd != null && sig != null) upDown(c, macd - sig) else null),
            Triple("Trend", i.optString("trend").ifBlank { "–" }, null),
            Triple("20-day avg", num1(i.dbl("sma20")), null), Triple("50-day avg", num1(i.dbl("sma50")), null),
            Triple("200-day avg", num1(i.dbl("sma200")), null),
            Triple("Volume", i.dbl("volume_vs_avg20")?.let { "%.1f× avg".format(it) } ?: "–", null),
            Triple("ATR 14", num1(i.dbl("atr14")), null),
            Triple("Bollinger", "${num1(i.dbl("bollinger_low"))}–${num1(i.dbl("bollinger_high"))}", null)))
        val lo = i.dbl("wk52_low"); val hi = i.dbl("wk52_high"); val cl = i.dbl("close")
        if (lo != null && hi != null && cl != null && hi > lo) {
            Text("52-week range", style = Type.small, color = c.muted)
            val f = ((cl - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(14.dp)) {
                Box(Modifier.align(Alignment.Center).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.field))
                Row(Modifier.fillMaxWidth()) {
                    if (f > 0f) Spacer(Modifier.weight(f))
                    Box(Modifier.size(14.dp).clip(CircleShape).background(c.ink))
                    if (f < 1f) Spacer(Modifier.weight(1f - f))
                }
            }
            Row { Text(num1(lo), style = Type.small, color = c.muted, modifier = Modifier.weight(1f)); Text(num1(hi), style = Type.small, color = c.muted) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FundamentalsPanel(f0: List<JSONObject>) {
    val c = LocalMochi.current
    val f = f0.filter { it.optString("field") != "checked" }
    if (f.isEmpty()) return
    val names = mapOf("eps" to "EPS", "pe" to "P/E", "pbv" to "P/B", "book_value" to "Book value", "market_cap" to "Market cap",
        "avg_volume_30d" to "Avg volume 30d")
    val latest = f.filter { it.optString("period") == "latest" && it.optString("field") in names }
        .sortedBy { names.keys.indexOf(it.optString("field")) }
    val eps = f.filter { it.optString("field") == "eps" && it.optString("period") != "latest" }.maxByOrNull { it.optString("period") }
    Panel("Fundamentals") {
        TileGrid((listOfNotNull(eps?.let { Triple("EPS ${it.optString("period").removePrefix("FY")}", it.optString("value"), null as Color?) }) +
            latest.map { Triple(names[it.optString("field")] ?: it.optString("field"), it.optString("value").let { v -> v.toDoubleOrNull()?.let { d ->
                if (d >= 1e7) "%.1f cr".format(d / 1e7) else if (d >= 1e5) "%.1f lakh".format(d / 1e5) else "%.2f".format(d) } ?: v }, null as Color?) }))
        val divs = f.filter { it.optString("field") == "cash_dividend_pct" || it.optString("field") == "bonus_pct" }
            .sortedByDescending { it.optString("period") }.take(8)
        if (divs.isNotEmpty()) {
            Text("Dividends", style = Type.small, color = c.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                divs.forEach { d -> Badge("${d.optString("period").removePrefix("FY")} ${if (d.optString("field") == "bonus_pct") "bonus" else "cash"} ${d.optString("value")}%",
                    if (d.optString("field") == "bonus_pct") c.violetBg else c.greenBg, if (d.optString("field") == "bonus_pct") c.violetInk else c.greenInk) }
            }
        }
        Text("From Merolagani and ShareSansar", style = Type.small, color = c.muted)
    }
}

@Composable
internal fun KeyValue(k: String, v: String) {
    val c = LocalMochi.current
    Row(Modifier.fillMaxWidth()) {
        Text(k, style = Type.small, color = c.muted, modifier = Modifier.weight(1f))
        Text(v, style = Type.small.copy(fontWeight = Type.strong), color = c.ink, textAlign = TextAlign.End)
    }
}

internal val LINE_OPTIONS = listOf("sma20" to "20-day average", "sma50" to "50-day average", "sma200" to "200-day average",
    "boll" to "Bollinger bands", "sr" to "Support / resistance", "buy" to "Your buy price", "stop" to "Stop-loss",
    "target" to "Target", "wk52" to "52-week high / low", "rsi" to "RSI pane", "macd" to "MACD pane")

/** Candlesticks drawn here (no chart library: the app stays small). Pinch to zoom, drag to move back in time;
 *  "Lines" picks the overlays. Averages and bands are worked out from all the bars, so a 200-day line works zoomed in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PriceChart(all: List<JSONObject>, ind: JSONObject?, buy: Double?, stop: Double?, target: Double?,
                        control: JSONObject? = null) {
    val c = LocalMochi.current
    if (all.size < 2) { Text("Not enough days yet.", style = Type.small, color = c.muted); return }
    var count by remember(all.size) { mutableIntStateOf(min(60, all.size)) }
    var end by remember(all.size) { mutableIntStateOf(all.size) }
    var drag by remember { mutableFloatStateOf(0f) }
    var on by remember { mutableStateOf(setOf("sma20", "sr", "buy", "stop", "target")) }
    var menu by remember { mutableStateOf(false) }
    // Samir drives the popup chart: its range, lines and drawings (`mochi-phone chart`); each new one is drawn in, step by step
    val seq = control?.optLong("seq") ?: 0L
    LaunchedEffect(seq, all.size) {
        if (control == null) return@LaunchedEffect
        count = when (control.optString("range").lowercase()) { "1m" -> 21; "3m" -> 63; "6m" -> 125; "1y", "all" -> all.size; else -> count }
            .coerceIn(min(10, all.size), all.size)
        end = all.size
        control.optJSONArray("show")?.let { a -> on = (0 until a.length()).map { a.optString(it) }.toSet() }
    }
    val drawn = remember { Animatable(1f) }
    LaunchedEffect(seq) { if (control != null) { drawn.snapTo(0f); drawn.animateTo(1f, tween(2800, easing = LinearEasing)) } }
    val lineChart = control?.optString("type") == "line"
    val dayIndex = remember(all) { all.withIndex().associate { (i, b) -> b.optString("day") to i } }
    val levels = control?.optJSONArray("levels").objs()
    val trends = control?.optJSONArray("trends").objs()
    val marks = control?.optJSONArray("marks").objs()
    val closes = remember(all) { all.map { it.optDouble("close") } }
    val avgs = remember(all) {
        listOf(20, 50, 200).associateWith { n -> closes.indices.map { i -> if (i < n - 1) null else closes.subList(i - n + 1, i + 1).average() } }
    }
    val sd20 = remember(all) {
        closes.indices.map { i -> if (i < 19) null else closes.subList(i - 19, i + 1).let { w -> val m = w.average(); sqrt(w.sumOf { (it - m) * (it - m) } / 20) } }
    }
    val recent = all.takeLast(20)
    val lines = buildList<Triple<Double, String, Color>> {
        if ("buy" in on) buy?.let { add(Triple(it, "Your buy", c.blueInk)) }
        if ("stop" in on) stop?.let { add(Triple(it, "Stop", c.danger)) }
        if ("target" in on) target?.let { add(Triple(it, "Target", c.greenInk)) }
        if ("sr" in on) {
            add(Triple(recent.maxOf { it.optDouble("high") }, "Resistance", c.amberInk))
            add(Triple(recent.minOf { it.optDouble("low") }, "Support", c.greenInk))
        }
        if ("wk52" in on) { ind?.dbl("wk52_high")?.let { add(Triple(it, "52w high", c.muted)) }; ind?.dbl("wk52_low")?.let { add(Triple(it, "52w low", c.muted)) } }
        levels.forEach { l -> l.dbl("price")?.let { add(Triple(it, l.optString("label"), levelColor(c, l.optString("kind")))) } }
    }
    val e = end.coerceIn(min(count, all.size), all.size)
    val st = max(0, e - count)
    val bars = all.subList(st, e)
    val highs = bars.map { it.optDouble("high") }.filter { !it.isNaN() }
    val lows = bars.map { it.optDouble("low") }.filter { !it.isNaN() }
    val near = lines.map { it.first }.filter { it > (lows.minOrNull() ?: 0.0) * 0.9 && it < (highs.maxOrNull() ?: 1.0) * 1.1 }
    val lo = (lows + near).minOrNull() ?: 0.0; val hi = (highs + near).maxOrNull() ?: 1.0; val span = (hi - lo).takeIf { it > 0 } ?: 1.0
    val vols = bars.map { it.optDouble("volume").takeIf { v -> !v.isNaN() } ?: 0.0 }
    val vmax = vols.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val tm = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                listOf("1M" to 21, "3M" to 63, "6M" to 125, "1Y" to all.size).forEach { (label, n) ->
                    val sel = count == min(n, all.size) && e == all.size
                    Text(label, style = Type.small.copy(fontWeight = if (sel) Type.strong else null), color = if (sel) c.bg else c.ink,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (sel) c.ink else c.field)
                            .clickable(role = Role.Button) { count = min(n, all.size); end = all.size }.padding(horizontal = Space.m, vertical = 4.dp))
                }
            }
            Box {
                Text("Lines ▾", style = Type.small.copy(fontWeight = Type.strong), color = c.ink,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.field).clickable(role = Role.Button) { menu = true }.padding(horizontal = Space.m, vertical = 4.dp))
                DropdownMenu(menu, { menu = false }) {
                    LINE_OPTIONS.forEach { (k, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { on = if (k in on) on - k else on + k },
                            leadingIcon = { Checkbox(checked = k in on, onCheckedChange = null) })
                    }
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(240.dp).pointerInput(all.size) {
            detectTransformGestures { _, pan, zoom, _ ->
                count = (count / zoom).roundToInt().coerceIn(min(10, all.size), all.size)
                drag += pan.x / (size.width.toFloat() / count)
                val shift = drag.toInt()
                if (shift != 0) { end = (end - shift).coerceIn(count, all.size); drag -= shift }
            }
        }) {
            val w = size.width / bars.size
            fun y(v: Double) = size.height * (1 - ((v - lo) / span).toFloat())
            fun stage(k: Int) = ((drawn.value * 4f) - k).coerceIn(0f, 1f)   // 0 averages, 1 levels, 2 trends, 3 marks
            fun xOf(i: Int) = w * (i - st) + w / 2
            if (lineChart) {
                val p = Path()
                bars.forEachIndexed { i, b -> val v = b.optDouble("close"); if (!v.isNaN()) { if (i == 0) p.moveTo(xOf(st + i), y(v)) else p.lineTo(xOf(st + i), y(v)) } }
                drawPath(p, c.ink, style = Stroke(width = 2.dp.toPx()))
            } else bars.forEachIndexed { i, b ->
                val o = b.optDouble("open").takeIf { !it.isNaN() } ?: b.optDouble("close")
                val cl = b.optDouble("close"); val h = b.optDouble("high"); val l = b.optDouble("low")
                if (cl.isNaN() || h.isNaN() || l.isNaN()) return@forEachIndexed
                val color = if (cl >= o) c.greenInk else c.danger
                val cx = w * i + w / 2
                drawLine(color, Offset(cx, y(h)), Offset(cx, y(l)), max(1f, w * 0.12f))
                val top = y(max(o, cl)); val bottom = y(min(o, cl))
                drawRect(color, Offset(cx - w * 0.35f, top), Size(w * 0.7f, max(1.5f, bottom - top)))
            }
            fun series(v: List<Double?>, color: Color, width: Float) {
                val p = Path(); var started = false
                for (i in st until st + ((e - st) * stage(0)).roundToInt()) {
                    val a = v[i] ?: continue
                    val px = w * (i - st) + w / 2
                    if (!started) { p.moveTo(px, y(a)); started = true } else p.lineTo(px, y(a))
                }
                if (started) drawPath(p, color, style = Stroke(width = width))
            }
            if ("sma20" in on) series(avgs[20]!!, c.blueInk, 1.5.dp.toPx())
            if ("sma50" in on) series(avgs[50]!!, c.amberInk, 1.5.dp.toPx())
            if ("sma200" in on) series(avgs[200]!!, c.violetInk, 1.5.dp.toPx())
            if ("boll" in on) {
                val mid = avgs[20]!!
                series(mid.indices.map { i -> mid[i]?.let { m -> sd20[i]?.let { m + 2 * it } } }, c.violetInk.copy(alpha = 0.5f), 1.dp.toPx())
                series(mid.indices.map { i -> mid[i]?.let { m -> sd20[i]?.let { m - 2 * it } } }, c.violetInk.copy(alpha = 0.5f), 1.dp.toPx())
            }
            val f = stage(1)
            if (f > 0f) lines.forEach { (v, label, color) ->
                if (v !in lo..hi) return@forEach
                drawLine(color, Offset(0f, y(v)), Offset(size.width * f, y(v)), 1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                val t = "$label ${"%.1f".format(v)}"
                val m = tm.measure(t, labelStyle)
                drawText(tm, t, Offset(size.width - m.size.width - 4f, (y(v) - m.size.height - 2f).coerceAtLeast(0f)), labelStyle.copy(color = color.copy(alpha = f)))
            }
            // trend lines Samir drew: from one (day, price) to another, drawn in from the left
            val g = stage(2)
            if (g > 0f) clipRect {
                trends.forEach { t ->
                    val a = t.optJSONArray("from"); val b = t.optJSONArray("to")
                    val i0 = dayIndex[a?.optString(0)] ?: return@forEach; val i1 = dayIndex[b?.optString(0)] ?: return@forEach
                    val p0 = Offset(xOf(i0), y(a!!.optDouble(1))); val p1 = Offset(xOf(i1), y(b!!.optDouble(1)))
                    val color = levelColor(c, t.optString("kind"))
                    drawLine(color, p0, p0 + (p1 - p0) * g, 2.dp.toPx())
                    if (g >= 1f && t.optString("label").isNotBlank())
                        drawText(tm, t.optString("label"), Offset(p1.x.coerceAtMost(size.width - 90f).coerceAtLeast(0f), (p1.y - 28f).coerceAtLeast(0f)), labelStyle.copy(color = color))
                }
            }
            // candle patterns Samir pointed at: a marker over the bar with its name
            val h = stage(3)
            if (h > 0f) marks.forEach { m ->
                val i = dayIndex[m.optString("day")] ?: return@forEach
                if (i !in st until e) return@forEach
                val top = all[i].optDouble("high").takeIf { !it.isNaN() } ?: return@forEach
                val color = levelColor(c, m.optString("kind")).copy(alpha = h)
                drawCircle(color, max(3f, w * 0.3f).coerceAtMost(8f), Offset(xOf(i), (y(top) - 10f).coerceAtLeast(6f)))
                val label = m.optString("label").take(18)
                if (label.isNotBlank()) {
                    val mm = tm.measure(label, labelStyle)
                    drawText(tm, label, Offset((xOf(i) - mm.size.width / 2f).coerceIn(0f, max(0f, size.width - mm.size.width)),
                        (y(top) - 14f - mm.size.height).coerceAtLeast(0f)), labelStyle.copy(color = color))
                }
            }
            drawText(tm, "%.1f".format(hi), Offset(2f, 0f), labelStyle.copy(color = c.muted))
            val bl = tm.measure("%.1f".format(lo), labelStyle)
            drawText(tm, "%.1f".format(lo), Offset(2f, size.height - bl.size.height), labelStyle.copy(color = c.muted))
        }
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val w = size.width / bars.size
            bars.forEachIndexed { i, b ->
                val up = b.optDouble("close") >= (b.optDouble("open").takeIf { !it.isNaN() } ?: b.optDouble("close"))
                val h = (vols[i] / vmax).toFloat() * size.height
                drawRect(if (up) c.greenInk.copy(alpha = 0.45f) else c.danger.copy(alpha = 0.45f), Offset(w * i + w * 0.15f, size.height - h), Size(w * 0.7f, h))
            }
        }
        if ("rsi" in on) IndicatorPane("RSI 14 (above 70 stretched, below 30 washed out)", rsiSeries(closes), st, e,
            listOf(30.0, 70.0), 0.0, 100.0, c.violetInk)
        if ("macd" in on) {
            val (macd, signal) = remember(all) { macdSeries(closes) }
            IndicatorPane("MACD 12/26 (blue) vs signal 9 (amber)", macd, st, e, listOf(0.0), null, null, c.blueInk, signal, c.amberInk)
        }
        val legend = buildList {
            if ("sma20" in on) add("20-day" to c.blueInk); if ("sma50" in on) add("50-day" to c.amberInk)
            if ("sma200" in on) add("200-day" to c.violetInk); if ("boll" in on) add("Bollinger" to c.violetInk.copy(alpha = 0.5f))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            legend.forEach { (label, color) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(width = 12.dp, height = 3.dp).background(color)); Text(label, style = Type.small, color = c.muted)
                }
            }
            Text("${bars.first().optString("day")} – ${bars.last().optString("day")} · pinch to zoom, drag to move", style = Type.small, color = c.muted)
        }
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
    var autoIpo by remember(cur) { mutableStateOf(cur.optBoolean("auto_apply_ipo", false)) }
    val fields = listOf("max_per_trade" to "Max money per trade (Rs)", "max_pct_one_stock" to "Max % of the book in one stock",
        "daily_loss_limit" to "Daily loss limit (Rs)", "weekly_loss_limit" to "Weekly loss limit (Rs)",
        "cgt_short" to "Capital gains tax ≤ 1 year (%)", "cgt_long" to "Capital gains tax > 1 year (%)")
    val defaults = mapOf("max_per_trade" to "50000", "max_pct_one_stock" to "25", "daily_loss_limit" to "10000",
        "weekly_loss_limit" to "25000", "cgt_short" to "7.5", "cgt_long" to "5")
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
                        .background(if (on) c.ink else c.field).selectable(selected = on, role = Role.RadioButton) { mode = k }.padding(vertical = Space.m))
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-apply IPOs", style = Type.body, color = c.ink)
                Text("The daily MeroShare check (16:10) applies by itself for IPOs of ordinary shares for the general public, with the minimum shares (usually 10), once each. Others stay a card for you.",
                    style = Type.small, color = c.muted)
            }
            Switch(autoIpo, { autoIpo = it }, colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.bg))
        }
        fields.forEach { (k, label) ->
            Text(label, style = Type.small, color = c.muted)
            app.mochi.phone.ui.Field(values[k]!!.value, { values[k]!!.value = it }, label, singleLine = true)
        }
        Text("NEPSE holidays (YYYY-MM-DD, comma-separated): no trading, T+2 skips them", style = Type.small, color = c.muted)
        app.mochi.phone.ui.Field(holidays, { holidays = it }, "2026-11-12, 2026-12-25", singleLine = false)
        Pill("Save trading settings", true, Modifier.fillMaxWidth()) {
            val out = JSONObject().put("mode", mode).put("auto_trade", auto).put("auto_apply_ipo", autoIpo)
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
internal fun MeroShareAccount() {
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
    var pin by remember { mutableStateOf("") }
    var crn by remember { mutableStateOf("") }
    var note2 by remember { mutableStateOf("") }
    Text("For applying to IPOs: your MeroShare transaction PIN (4 digits) and CRN (from your bank's C-ASBA). Same vault, root-only.",
        style = Type.small, color = c.muted, modifier = Modifier.padding(top = Space.s))
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.field).padding(Space.l)) {
        if (pin.isEmpty()) Text("Transaction PIN", style = Type.body, color = c.muted)
        androidx.compose.foundation.text.BasicTextField(pin, { v -> pin = v.filter { it.isDigit() }.take(4) }, singleLine = true,
            textStyle = Type.body.copy(color = c.ink), cursorBrush = androidx.compose.ui.graphics.SolidColor(c.ink),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Transaction PIN" })
    }
    app.mochi.phone.ui.Field(crn, { crn = it.trim() }, "CRN number", singleLine = true)
    Pill("Save PIN + CRN (fingerprint)", true, Modifier.fillMaxWidth()) {
        if (pin.length != 4 || crn.isBlank()) { note2 = "PIN is 4 digits; CRN too, please."; return@Pill }
        VaultUnlock.send(a, "Save the IPO PIN and CRN", "/trading/creds",
            JSONObject().put("meroshare", JSONObject().put("pin", pin).put("crn", crn))) { err ->
            note2 = err ?: "Saved on the server."
            if (err == null) { pin = ""; crn = "" }
        }
    }
    if (note2.isNotEmpty()) Text(note2, style = Type.small, color = c.muted)
}

// ---- Samir's chart popup (`mochi-phone chart`): the chart he drives, what the candles say, his view ----

/** Colour for a level, trend line or mark Samir drew, by its kind. */
internal fun levelColor(c: MochiColors, kind: String): Color = when (kind.lowercase()) {
    "support", "up", "bullish", "target" -> c.greenInk
    "resistance", "neutral" -> c.amberInk
    "entry", "buy" -> c.blueInk
    "stop", "down", "bearish" -> c.danger
    else -> c.violetInk
}

internal fun emaSeries(x: List<Double?>, n: Int): List<Double?> {
    val out = MutableList<Double?>(x.size) { null }
    val k = 2.0 / (n + 1); var prev: Double? = null; var seen = 0; var sum = 0.0
    for (i in x.indices) {
        val v = x[i] ?: continue
        if (prev == null) { sum += v; seen++; if (seen == n) { prev = sum / n; out[i] = prev } }
        else { prev = v * k + prev * (1 - k); out[i] = prev }
    }
    return out
}

/** RSI 14, Wilder's smoothing (as mochi-market's). */
internal fun rsiSeries(closes: List<Double>, n: Int = 14): List<Double?> {
    val x = closes.map { it.takeIf { v -> !v.isNaN() } }
    val out = MutableList<Double?>(x.size) { null }
    var g = 0.0; var l = 0.0; var count = 0
    for (i in 1 until x.size) {
        val a = x[i] ?: continue; val b = x[i - 1] ?: continue
        val d = a - b; count++
        if (count <= n) { g += max(d, 0.0); l += max(-d, 0.0); if (count == n) { g /= n; l /= n } else continue }
        else { g = (g * (n - 1) + max(d, 0.0)) / n; l = (l * (n - 1) + max(-d, 0.0)) / n }
        out[i] = if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l)
    }
    return out
}

/** MACD (EMA 12 − EMA 26) and its 9-day signal line. */
internal fun macdSeries(closes: List<Double>): Pair<List<Double?>, List<Double?>> {
    val x = closes.map { it.takeIf { v -> !v.isNaN() } }
    val e12 = emaSeries(x, 12); val e26 = emaSeries(x, 26)
    val macd = x.indices.map { i -> e12[i]?.let { a -> e26[i]?.let { a - it } } }
    return macd to emaSeries(macd, 9)
}

/** A small indicator chart under the prices, over the same days. */
@Composable
internal fun IndicatorPane(label: String, v: List<Double?>, st: Int, e: Int, guides: List<Double>, min0: Double?, max0: Double?,
                          color: Color, v2: List<Double?>? = null, color2: Color = color) {
    val c = LocalMochi.current
    Text(label, style = Type.small, color = c.muted)
    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        val win = (st until e).flatMap { listOfNotNull(v.getOrNull(it), v2?.getOrNull(it)) }
        if (win.isEmpty()) return@Canvas
        val lo = min0 ?: (win + guides).min(); val hi = max0 ?: (win + guides).max()
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val w = size.width / max(1, e - st)
        fun y(x: Double) = size.height * (1 - ((x - lo) / span).toFloat())
        guides.forEach { gd ->
            drawLine(c.line, Offset(0f, y(gd)), Offset(size.width, y(gd)), 1.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        }
        fun line(s: List<Double?>, col: Color) {
            val p = Path(); var started = false
            for (i in st until e) {
                val a = s.getOrNull(i) ?: continue
                val px = w * (i - st) + w / 2
                if (!started) { p.moveTo(px, y(a)); started = true } else p.lineTo(px, y(a))
            }
            if (started) drawPath(p, col, style = Stroke(width = 1.5.dp.toPx()))
        }
        line(v, color); v2?.let { line(it, color2) }
    }
}

/** The popup's page: the chart Samir drives, his words now, what each candle pattern means, and his saved view. */
@Composable
internal fun ChartPopup(spec: JSONObject, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    val symbol = spec.optString("symbol")
    var d by remember(symbol) { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(symbol) {
        repeat(12) {   // a stock the server hasn't prepared is ready ~10 s after it's asked for
            io(scope, { Trading.stock(ctx, symbol) }) { d = it }
            delay(if (d?.optBoolean("pending") == true || d == null) 3_000 else 60_000)
            if (d != null && d?.optBoolean("pending") != true) return@LaunchedEffect
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).systemBarsPadding().padding(Space.m)) {
        Column(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(c.bg)) {
            Row(Modifier.fillMaxWidth().padding(start = Space.l, end = Space.s, top = Space.s, bottom = Space.s),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(listOf(symbol, spec.optString("range").uppercase()).filter { it.isNotBlank() }.joinToString(" · "),
                        style = Type.title, color = c.ink)
                    spec.optString("title").takeIf { it.isNotBlank() }?.let {
                        Text(it, style = Type.small, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                RoundButton(Icons2.Close, "Close", c.field, c.ink, 44.dp, onClose)
            }
            val x = d
            if (x == null || x.optBoolean("pending")) {
                Text("Getting $symbol's chart…", style = Type.body, color = c.muted, modifier = Modifier.padding(Space.xl))
                return@Column
            }
            val bars = x.optJSONArray("bars").objs()
            val pos = x.optJSONObject("position")
            val mine = pos?.optJSONObject("live") ?: pos?.optJSONObject("paper")
            val ind = x.optJSONObject("indicators")
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.m)) {
                PriceChart(bars, ind, mine?.dbl("avg"), mine?.optString("stop")?.toDoubleOrNull(),
                    mine?.optString("target")?.toDoubleOrNull(), spec)
                spec.optString("note").takeIf { it.isNotBlank() }?.let { note ->
                    Panel("Samir now") { Text(note, style = Type.body, color = c.ink) }
                }
                val marks = spec.optJSONArray("marks").objs()
                if (marks.isNotEmpty()) Panel("What the candles say") {
                    marks.forEach { m ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text("${m.optString("day").takeLast(5)}  ${m.optString("label")}", style = Type.small.copy(fontWeight = Type.strong),
                                color = levelColor(c, m.optString("kind")))
                            m.optString("meaning").takeIf { it.isNotBlank() }?.let { Text(it, style = Type.small, color = c.muted) }
                        }
                    }
                }
                VerdictPanel(mine?.optJSONObject("verdict"))
                Text("A reading of past prices: patterns and lines raise or lower the odds, they never promise the next move.",
                    style = Type.small, color = c.muted)
                Spacer(Modifier.height(Space.l))
            }
        }
    }
}
