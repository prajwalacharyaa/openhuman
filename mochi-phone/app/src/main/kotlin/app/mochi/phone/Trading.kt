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
    x > 0 -> "+%.1f%%".format(x)
    x < 0 -> "−%.1f%%".format(-x)
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
        SummaryStrip(mode, b, nepse?.dbl("pct"), snap.optJSONObject("money")?.optJSONObject(mode),
            snap.optJSONObject("settings")?.optJSONArray("holidays")) { ctx.startActivity(Intent(ctx, ResearchActivity::class.java)) }
        val cards = snap.optJSONArray("cards").objs()
        if (cards.isNotEmpty()) Text("Needs you (${cards.size})", style = Type.title, color = c.ink, modifier = Modifier.padding(top = Space.s))
        cards.forEach { TradeCard(it, onChanged) }
    }
}

/** Is NEPSE trading right now? Monday to Friday 11:00-15:00 Kathmandu time, not a holiday. */
internal fun marketOpen(holidays: JSONArray? = null): Boolean {
    val now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kathmandu"))
    val off = (0 until (holidays?.length() ?: 0)).map { holidays!!.optString(it) }.toSet()
    val t = now.hour * 60 + now.minute
    return now.dayOfWeek.value in 1..5 && t in 660 until 900 && now.toLocalDate().toString() !in off
}

/** "● LIVE" in green while NEPSE is trading, red when it's closed (PAPER: the pretend book, same dot). */
@Composable
private fun BookPill(mode: String, open: Boolean) {
    val c = LocalMochi.current
    val dot = if (open) c.greenInk else c.danger
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(c.field).padding(horizontal = 10.dp, vertical = 4.dp)
        .semantics { contentDescription = "$mode book, market ${if (open) "open" else "closed"}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(if (mode == "paper") "PAPER" else "LIVE", style = Type.small.copy(fontWeight = Type.strong),
            color = if (mode == "paper") c.blueInk else dot)
    }
}

/** Rs 1,57,659.80 with a small "Rs", the rupees big and the paisa quiet. */
@Composable
private fun Amount(x: Double, big: Boolean = true) {
    val c = LocalMochi.current
    val s = rs(x, true)
    val neg = s.startsWith("−")
    val num = s.removePrefix("−").removePrefix("Rs ")
    Row {
        Text((if (neg) "−" else "") + "Rs ", style = Type.body, color = c.muted, modifier = Modifier.alignByBaseline())
        Text(num.substringBefore('.'), color = c.ink, modifier = Modifier.alignByBaseline(),
            style = TextStyle(fontSize = if (big) 34.sp else 20.sp, lineHeight = if (big) 40.sp else 26.sp, fontWeight = FontWeight.SemiBold))
        Text("." + num.substringAfter('.', "00"), style = Type.body, color = c.muted, modifier = Modifier.alignByBaseline())
    }
}

/** A small rounded arrow, up-right for a gain, down-right for a loss (drawn, so it looks the same on every phone). */
@Composable
internal fun TrendArrow(up: Boolean, color: Color, size: androidx.compose.ui.unit.Dp = 12.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * 0.16f
        val from = if (up) Offset(w * 0.2f, h * 0.8f) else Offset(w * 0.2f, h * 0.2f)
        val to = if (up) Offset(w * 0.8f, h * 0.2f) else Offset(w * 0.8f, h * 0.8f)
        drawLine(color, from, to, sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, to, Offset(w * 0.38f, to.y), sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, to, Offset(to.x, if (up) h * 0.62f else h * 0.38f), sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** ↗ 9,951.75 | 6.7%  in green, or ↘ in red. */
@Composable
private fun ChangeChip(amount: Double, pctV: Double?) {
    val c = LocalMochi.current
    val up = amount >= 0
    val ink = if (up) c.greenInk else c.danger
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(if (up) c.greenBg else c.amberBg).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        TrendArrow(up, ink, 11.dp)
        Text(rs(abs(amount)).removePrefix("Rs ") + (pctV?.let { "  |  %.1f%%".format(abs(it)) } ?: ""),
            style = Type.small.copy(fontWeight = Type.strong), color = ink)
    }
}

/** Label over a value, small: the card's and the rows' figures. */
@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier, ink: Color? = null, end: Boolean = false) {
    val c = LocalMochi.current
    Column(modifier, horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Text(label, style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = Type.body.copy(fontWeight = Type.strong), color = ink ?: c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private data class SummaryPage(val label: String, val value: Double, val change: Double, val pct: Double?,
                               val stats: List<Triple<String, String, Color?>>)

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SummaryStrip(mode: String, b: JSONObject, nepsePct: Double?, money: JSONObject? = null, holidays: JSONArray? = null,
                         onOpen: (() -> Unit)? = null) {
    val c = LocalMochi.current
    val groups = b.optJSONObject("groups")
    val sold = b.optJSONObject("if_sold")
    val invested = b.optDouble("invested", 0.0)
    val pages = buildList<SummaryPage> {
        val value = b.optDouble("value", 0.0)
        val todayPct = if (value > 0) b.optDouble("today", 0.0) / value * 100 else null
        if (sold != null) add(SummaryPage("If you sell everything today", sold.optDouble("net"), sold.optDouble("pnl"),
            if (invested > 0) sold.optDouble("pnl") / invested * 100 else null, listOf(
                Triple("Fees", rs(sold.optDouble("fees")), null),
                Triple("Tax", rs(sold.optDouble("tax")), null),
                Triple("Invested", rs(invested), null))))
        add(SummaryPage("Portfolio value", value, b.optDouble("pnl", 0.0), b.dbl("pnl_pct"), listOf(
            Triple("Invested", rs(invested), null),
            Triple("Your stocks today", pct(todayPct), upDown(c, todayPct)),
            Triple("NEPSE today", pct(nepsePct), upDown(c, nepsePct)))))
        listOf("ipo" to "IPO shares", "secondary" to "Bought in the market", "other" to "Bonus, rights & merger").forEach { (k, label) ->
            groups?.optJSONObject(k)?.let { g ->
                val cost = g.optDouble("cost")
                add(SummaryPage(label, g.optDouble("value"), g.optDouble("pnl"), if (cost > 0) g.optDouble("pnl") / cost * 100 else null,
                    listOf(Triple("Invested", rs(cost), null), Triple("Shares", "${g.optInt("qty")}", null), Triple("Stocks", "${g.optInt("stocks")}", null))))
            }
        }
    }
    // "If you sell everything today" sits first, one swipe to the left; the card opens on Portfolio value
    val pager = rememberPagerState(initialPage = if (sold != null) 1 else 0, pageCount = { pages.size })
    val open = marketOpen(holidays)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.card)
        .then(if (onOpen != null) Modifier.clickable(onClickLabel = "Open portfolio and market", onClick = onOpen) else Modifier)
        .padding(horizontal = Space.xl, vertical = Space.l)) {
        HorizontalPager(pager, Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, pageSpacing = Space.xl) { i ->
            val pg = pages[i]
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pg.label, style = Type.small, color = c.muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BookPill(mode, open)
                }
                FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    Amount(pg.value)
                    Box(Modifier.align(Alignment.CenterVertically)) { ChangeChip(pg.change, pg.pct) }
                }
                Row(Modifier.fillMaxWidth().padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    pg.stats.forEachIndexed { k, (label, v, ink) -> MiniStat(label, v, Modifier.weight(1f), ink, end = k == pg.stats.size - 1) }
                }
            }
        }
        if (pages.size > 1) Row(Modifier.fillMaxWidth().padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            repeat(pages.size) { i ->
                val on = pager.currentPage == i
                Box(Modifier.size(if (on) 8.dp else 6.dp).clip(CircleShape).background(if (on) c.ink else c.muted.copy(alpha = 0.35f)))
            }
        }
        money?.let { m ->
            Box(Modifier.fillMaxWidth().padding(vertical = Space.m).height(1.dp).background(c.line))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Buying power", style = Type.small, color = c.muted)
                    Text(rs(m.optDouble("available"), true), style = Type.body.copy(fontWeight = Type.strong), color = c.ink)
                }
                val toBank = m.optJSONArray("to_bank").objs()
                if (toBank.isNotEmpty()) Column(horizontalAlignment = Alignment.End) {
                    Text("To your bank ${toBank.first().optString("due").takeLast(5)}", style = Type.small, color = c.muted)
                    Text(rs(m.optDouble("to_bank_total"), true), style = Type.body.copy(fontWeight = Type.strong), color = c.ink)
                } else m.optString("collateral_updated").takeIf { it.isNotBlank() && it != "null" }?.let {
                    Text("set ${it.take(10)}", style = Type.small, color = c.muted)
                }
            }
        }
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
        "ipo" -> "IPO ${k.optString("symbol")}: ${k.optString("company")}" to c.violetInk
        else -> "⚠ ${k.optString("text")}" to c.amberInk
    }
    var kitta by remember(k.optString("id")) { mutableStateOf(k.optInt("min", 10).toString()) }
    var ipoNote by remember(k.optString("id")) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).clickable { open = true }.padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (k.optString("mode") == "paper") Badge("PAPER", c.blueBg, c.blueInk)
            else if (k.optString("mode") == "live") Badge("LIVE", c.greenBg, c.greenInk)
            Text(title, style = Type.body.copy(fontWeight = Type.strong), color = accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val sub = when (type) {
            "ticket" -> "${k.optString("mode").uppercase()} · fees ${rs(k.optDouble("fees"), true)} · ${k.optString("why")}"
            "suggestion" -> "${k.optString("mode").uppercase()} · ${k.optString("why")}"
            "ipo" -> "${k.optString("share_type")} · ${k.optString("group")} · ${k.optString("sub_group")} · ${k.optInt("min")}-${k.optInt("max")} shares · closes ${k.optString("closes").take(12)}"
            else -> "Stays here until it's done."
        }
        Text(sub, style = Type.small, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            when (type) {
                "ticket" -> {
                    val live = k.optString("mode") == "live"
                    val sell = k.optString("side") == "sell"
                    Pill(if (live) (if (sell) "I sold it in TMS" else "I bought it in TMS") else "Fill in paper (pretend)", true, Modifier.weight(1f), 40.dp) {
                        if (!busy) { if (k.optString("mode") == "live") placing = true else send("placed") }
                    }
                    Pill("Not now", false, Modifier.weight(1f), 40.dp) { if (!busy) send("notnow") }
                }
                "suggestion" -> {
                    Pill("Approve", true, Modifier.weight(1f), 40.dp) { if (!busy) send("approve") }
                    Pill("Reject", false, Modifier.weight(1f), 40.dp) { if (!busy) send("reject") }
                }
                "ipo" -> {
                    if (k.optBoolean("applied")) {
                        Text("✓ Applied · ${k.optString("applied_note")}", style = Type.small.copy(fontWeight = Type.strong), color = c.greenInk)
                    } else {
                        Box(Modifier.weight(1f)) { app.mochi.phone.ui.Field(kitta, { v -> kitta = v.filter { it.isDigit() }.take(6) }, "Shares", singleLine = true) }
                        Pill(if (busy) "Applying…" else "Apply (fingerprint)", true, Modifier.weight(1f), 40.dp) {
                            val a = ctx as? Activity
                            val q = kitta.toIntOrNull()
                            if (busy || a == null) return@Pill
                            if (q == null || q < k.optInt("min", 10) || q > k.optInt("max", 1_000_000)) { ipoNote = "Between ${k.optInt("min")} and ${k.optInt("max")} shares."; return@Pill }
                            busy = true
                            VaultUnlock.send(a, "Apply for ${k.optString("symbol")}: $q shares", "/trading/ipo-apply",
                                JSONObject().put("companyShareId", k.optInt("id")).put("kitta", q)) { err ->
                                busy = false
                                ipoNote = err ?: "Sent once. MeroShare's answer comes as a notification."
                                scope.launch { delay(3_000); onChanged() }
                            }
                        }
                    }
                }
                else -> {
                    Pill("Done", true, Modifier.weight(1f), 40.dp) { if (!busy) send("done") }
                    Pill("Remind in 1 h", false, Modifier.weight(1f), 40.dp) { if (!busy) send("snooze") }
                }
            }
        }
    }
    if (ipoNote.isNotEmpty()) Text(ipoNote, style = Type.small, color = c.muted, modifier = Modifier.padding(horizontal = Space.m))
    if (open) AlertDialog(onDismissRequest = { open = false }, confirmButton = { TextButton({ open = false }) { Text("Close") } },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(k.optString("why").ifBlank { k.optString("text") }, style = Type.body)
                if (type == "ticket") {
                    Text("Amount ${rs(k.optDouble("amount"), true)} · fees ${rs(k.optDouble("fees"), true)}", style = Type.small)
                    if (k.optString("stop").isNotBlank() && k.optString("stop") != "null") Text("Stop-loss ${k.optString("stop")}", style = Type.small)
                    if (k.optString("target").isNotBlank() && k.optString("target") != "null") Text("Target ${k.optString("target")}", style = Type.small)
                    Text(if (k.optString("mode") == "live") "Nothing is traded by tapping: you place it in TMS yourself, then tap \"I sold / bought it in TMS\" with the real price and quantity. It expires at the close."
                        else "PAPER: pretend money. \"Fill in paper\" moves only the paper book; nothing real happens.",
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
    /** A tab or a stock asked for by Mochi or Samir ("show my watchlist", "open NABIL"), with when (a new ask reapplies). */
    private var requested by mutableStateOf<Triple<String?, String?, Long>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        take(intent)
        setContent { MochiTheme { ResearchScreen(requested, onBack = { finish() }, onHome = {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(i: Intent?) {
        val tab = i?.getStringExtra(EXTRA_TAB); val stock = i?.getStringExtra(EXTRA_STOCK)
        if (tab != null || stock != null) requested = Triple(tab, stock, System.nanoTime())
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_STOCK = "stock"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResearchScreen(requested: Triple<String?, String?, Long>?, onBack: () -> Unit, onHome: () -> Unit) {
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
    val tabs = listOf("Trades", "Holdings", "IPO", "Watchlist", "Market", "Journal", "Calendar")
    val pager = rememberPagerState(initialPage = 1) { tabs.size }
    val tabRow = rememberLazyListState()
    LaunchedEffect(pager.currentPage) { tabRow.animateScrollToItem(max(0, pager.currentPage - 1)) }
    LaunchedEffect(requested) {
        val r = requested ?: return@LaunchedEffect
        r.first?.let { t -> tabs.indexOfFirst { it.equals(t, ignoreCase = true) }.takeIf { it >= 0 }?.let { pager.animateScrollToPage(it) } }
        r.second?.let { stock = it }
    }
    val snap = data?.optJSONObject("snapshot")
    val mode = view ?: snap?.optString("mode") ?: "paper"
    Box(Modifier.fillMaxSize().background(c.bg).systemBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons2.Back, "Back", c.field, c.ink, 44.dp, onBack)
                Text("Portfolio & market", style = Type.title, color = c.ink, modifier = Modifier.weight(1f).padding(horizontal = Space.m))
                Text(if (mode == "paper") "PAPER" else "LIVE", style = Type.small.copy(fontWeight = Type.strong),
                    color = if (mode == "paper") c.blueInk else c.greenInk,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (mode == "paper") c.blueBg else c.greenBg)
                        .clickable { view = if (mode == "paper") "live" else "paper" }.padding(horizontal = Space.m, vertical = Space.xs))
                Spacer(Modifier.width(Space.s))
                RoundButton(Icons2.Home, "Home", c.field, c.ink, 44.dp, onHome)
            }
            LazyRow(Modifier.fillMaxWidth(), state = tabRow, contentPadding = PaddingValues(horizontal = Space.l),
                horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                itemsIndexed(tabs) { i, t ->
                    val on = pager.currentPage == i
                    Text(t, style = Type.body.copy(fontWeight = if (on) Type.strong else null), color = if (on) c.bg else c.ink,
                        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) c.ink else c.field)
                            .clickable { scope.launch { pager.animateScrollToPage(i) } }.padding(horizontal = Space.l, vertical = Space.s))
                }
            }
            HorizontalPager(pager, Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) { page ->
                if (data == null) Text("Loading…", style = Type.body, color = c.muted, modifier = Modifier.padding(Space.xl))
                else when (page) {
                    0 -> TradesTab(data!!, mode) { stock = it }
                    1 -> HoldingsTab(data!!, mode, { stock = it }) { scope.launch { delay(2_000); tick++ } }
                    2 -> IpoTab(data!!) { scope.launch { delay(2_000); tick++ } }
                    3 -> WatchTab(data!!, { stock = it }) { scope.launch { delay(2_000); tick++ } }
                    4 -> MarketTab(data!!) { stock = it }
                    5 -> JournalTab(data!!, mode)
                    else -> CalendarTab(data!!) { stock = it }
                }
            }
        }
        // floating mute while a call is on (as on the Doing screen)
        if (Call.active) Box(Modifier.align(Alignment.BottomEnd).padding(Space.xl).size(64.dp).clip(CircleShape)
            .background(if (Call.muted || Call.frozen) c.danger else MuteFabIdle)
            .clickable(role = Role.Button) { if (Call.frozen) Call.unfreeze() else Call.muted = !Call.muted; tick++ }
            .semantics { contentDescription = if (Call.muted) "Unmute" else "Mute" }, contentAlignment = Alignment.Center) {
            LineIcon(if (Call.muted || Call.frozen) Icons2.MicOff else Icons2.Mic, Color.White, 28.dp)
        }
    }
    stock?.let { s ->
        val row = snap?.optJSONObject("books")?.optJSONObject(mode)?.optJSONArray("holdings").objs().firstOrNull { it.optString("symbol") == s }
        StockSheet(s, row) { stock = null }
    }
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
                                .clickable { if (sort == k) desc = !desc else { sort = k; desc = k != "name" } }
                                .padding(horizontal = Space.m, vertical = Space.xs))
                    }
                }
                if (mode == "live") Text("Fetch", style = Type.small.copy(fontWeight = Type.strong), color = c.blueInk,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable {
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
private fun HoldingRow(h: JSONObject, onTap: () -> Unit) {
    val c = LocalMochi.current
    val pnl = h.optDouble("pnl", 0.0)
    val pnlPct = h.dbl("pnl_pct")
    val since = h.optString("since").takeIf { it.isNotBlank() && it != "null" }?.let {
        runCatching { java.time.LocalDate.parse(it).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")) }.getOrDefault(it)
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).clickable(onClick = onTap).padding(Space.l),
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

// ---- tab: trades, every fill as it was recorded (real = you placed it in TMS and confirmed; paper = pretend) ----

@Composable
private fun TradesTab(data: JSONObject, mode: String, onStock: (String) -> Unit) {
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
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).clickable { onStock(t.optString("symbol")) }
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
private fun IpoTab(data: JSONObject, onChanged: () -> Unit) {
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
private fun Panel(title: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
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
private fun TileGrid(items: List<Triple<String, String, Color?>>, cols: Int = 3) {
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

private fun num1(v: Double?) = v?.let { "%.1f".format(it) } ?: "–"

@Composable
private fun PositionPanel(mine: JSONObject, holding: JSONObject?, ltp: Double?, stop: Double?, target: Double?) {
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
private fun VerdictPanel(v: JSONObject?) {
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
private fun SignalsPanel(x: JSONObject) {
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
private fun PerformancePanel(st: JSONObject) {
    val c = LocalMochi.current
    fun t(label: String, k: String) = Triple(label, pct(st.dbl(k)), upDown(c, st.dbl(k)))
    Panel("Performance") {
        TileGrid(listOf(t("1 week", "ret_1w"), t("1 month", "ret_1m"), t("3 months", "ret_3m"), t("1 year", "ret_1y"),
            Triple("Biggest drop", st.dbl("max_drop_pct")?.let { "%.1f%%".format(it) } ?: "–", c.danger),
            Triple("Daily move", st.dbl("daily_volatility_pct")?.let { "±%.1f%%".format(it) } ?: "–", null)))
    }
}

@Composable
private fun IndicatorsPanel(i: JSONObject) {
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
private fun FundamentalsPanel(f0: List<JSONObject>) {
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
private fun KeyValue(k: String, v: String) {
    val c = LocalMochi.current
    Row(Modifier.fillMaxWidth()) {
        Text(k, style = Type.small, color = c.muted, modifier = Modifier.weight(1f))
        Text(v, style = Type.small.copy(fontWeight = Type.strong), color = c.ink, textAlign = TextAlign.End)
    }
}

private val LINE_OPTIONS = listOf("sma20" to "20-day average", "sma50" to "50-day average", "sma200" to "200-day average",
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
                            .clickable { count = min(n, all.size); end = all.size }.padding(horizontal = Space.m, vertical = 4.dp))
                }
            }
            Box {
                Text("Lines ▾", style = Type.small.copy(fontWeight = Type.strong), color = c.ink,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.field).clickable { menu = true }.padding(horizontal = Space.m, vertical = 4.dp))
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
private fun levelColor(c: MochiColors, kind: String): Color = when (kind.lowercase()) {
    "support", "up", "bullish", "target" -> c.greenInk
    "resistance", "neutral" -> c.amberInk
    "entry", "buy" -> c.blueInk
    "stop", "down", "bearish" -> c.danger
    else -> c.violetInk
}

private fun emaSeries(x: List<Double?>, n: Int): List<Double?> {
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
private fun rsiSeries(closes: List<Double>, n: Int = 14): List<Double?> {
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
private fun macdSeries(closes: List<Double>): Pair<List<Double?>, List<Double?>> {
    val x = closes.map { it.takeIf { v -> !v.isNaN() } }
    val e12 = emaSeries(x, 12); val e26 = emaSeries(x, 26)
    val macd = x.indices.map { i -> e12[i]?.let { a -> e26[i]?.let { a - it } } }
    return macd to emaSeries(macd, 9)
}

/** A small indicator chart under the prices, over the same days. */
@Composable
private fun IndicatorPane(label: String, v: List<Double?>, st: Int, e: Int, guides: List<Double>, min0: Double?, max0: Double?,
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
