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

internal fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k) else null
internal fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

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
internal fun BookPill(mode: String, open: Boolean) {
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
internal fun Amount(x: Double, big: Boolean = true) {
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
internal fun ChangeChip(amount: Double, pctV: Double?) {
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
internal fun MiniStat(label: String, value: String, modifier: Modifier = Modifier, ink: Color? = null, end: Boolean = false) {
    val c = LocalMochi.current
    Column(modifier, horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Text(label, style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = Type.body.copy(fontWeight = Type.strong), color = ink ?: c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal data class SummaryPage(val label: String, val value: Double, val change: Double, val pct: Double?,
                               val stats: List<Triple<String, String, Color?>>)

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun SummaryStrip(mode: String, b: JSONObject, nepsePct: Double?, money: JSONObject? = null, holidays: JSONArray? = null,
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
        .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open portfolio and market", onClick = onOpen) else Modifier)
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
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).clickable(role = Role.Button) { open = true }.padding(Space.m),
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
internal fun PlacedDialog(k: JSONObject, onDismiss: () -> Unit, onDone: (Double, Int) -> Unit) {
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
internal fun ResearchScreen(requested: Triple<String?, String?, Long>?, onBack: () -> Unit, onHome: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var stock by remember { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf<String?>(null) }   // which book this page shows (default: the settings' mode)
    var loadError by remember { mutableStateOf("") }
    WhileVisible(tick) {
        // a failure says so (it used to show "Loading…" forever when the server couldn't be reached)
        while (true) { io(scope, { Trading.load(ctx) }, { loadError = Room.friendly(it) }) { data = it; loadError = "" }; delay(15_000) }
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
                Text("Portfolio & market", style = Type.title, color = c.ink, modifier = Modifier.weight(1f).padding(horizontal = Space.m).semantics { heading() })
                Text(if (mode == "paper") "PAPER" else "LIVE", style = Type.small.copy(fontWeight = Type.strong),
                    color = if (mode == "paper") c.blueInk else c.greenInk,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (mode == "paper") c.blueBg else c.greenBg)
                        .clickable(role = Role.Button, onClickLabel = if (mode == "paper") "Show the live account" else "Show the paper account") { view = if (mode == "paper") "live" else "paper" }.padding(horizontal = Space.m, vertical = Space.xs))
                Spacer(Modifier.width(Space.s))
                RoundButton(Icons2.Home, "Home", c.field, c.ink, 44.dp, onHome)
            }
            LazyRow(Modifier.fillMaxWidth(), state = tabRow, contentPadding = PaddingValues(horizontal = Space.l),
                horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                itemsIndexed(tabs) { i, t ->
                    val on = pager.currentPage == i
                    Text(t, style = Type.body.copy(fontWeight = if (on) Type.strong else null), color = if (on) c.bg else c.ink,
                        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) c.ink else c.field)
                            .selectable(selected = on, role = Role.Tab) { scope.launch { pager.animateScrollToPage(i) } }.padding(horizontal = Space.l, vertical = Space.s))
                }
            }
            Refreshable({ tick++ }, Modifier.fillMaxSize()) {
            HorizontalPager(pager, Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) { page ->
                if (data == null) Column(Modifier.padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Text(loadError.ifBlank { "Loading…" }, style = Type.body, color = if (loadError.isBlank()) c.muted else c.danger)
                    if (loadError.isNotBlank()) Pill("Try again", true) { loadError = ""; tick++ }
                }
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
