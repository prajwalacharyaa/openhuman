package app.mochi.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mochi.phone.ui.Field
import app.mochi.phone.ui.Icons2
import app.mochi.phone.ui.Label
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.Mascot
import app.mochi.phone.ui.MochiColors
import app.mochi.phone.ui.MochiTheme
import app.mochi.phone.ui.Mood
import app.mochi.phone.ui.RoundButton
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Type
import app.mochi.phone.ui.io
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A project's map, from its Graphify graph, as a drill-down that stays still: the project and its areas (Graphify's
 * communities) → an area and its key parts → one part, what it uses and what uses it. Each level draws one centre
 * with at most 12 neighbours around it (no physics, nothing moves on its own); everything else is in the list below.
 * Tap to go deeper, Back to come out, pinch to zoom.
 */
class MapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = intent.getStringExtra(EXTRA_REPO) ?: return finish()
        setContent { MochiTheme { MapScreen(repo) { finish() } } }
    }

    companion object {
        const val EXTRA_REPO = "repo"
        fun open(c: Context, repo: String) = c.startActivity(Intent(c, MapActivity::class.java).putExtra(EXTRA_REPO, repo))
    }
}

/** One part of the project (a file, function, component…). */
class Part(val id: String, val label: String, val type: String, val file: String, val where: String, val area: Int)

/** One link from a part: to [other], [rel] ("calls", "imports"…), [out] = this part points at the other. */
class Link(val other: String, val rel: String, val out: Boolean)

/** One area (a Graphify community): its name and its parts, most connected first. */
class Area(val id: Int, val name: String, val parts: List<String>)

/** The whole map, read once from graph.json. */
class ProjectMap(val parts: Map<String, Part>, val links: Map<String, List<Link>>, val areas: List<Area>) {
    fun degree(id: String) = links[id]?.size ?: 0
    fun label(id: String) = parts[id]?.label ?: id

    companion object {
        fun parse(json: String): ProjectMap {
            val g = JSONObject(json)
            val parts = HashMap<String, Part>()
            val names = HashMap<Int, String>()
            val nodes = g.getJSONArray("nodes")
            for (i in 0 until nodes.length()) {
                val n = nodes.getJSONObject(i)
                val id = n.optString("id")
                val area = n.optInt("community", -1)
                parts[id] = Part(id, n.optString("label").ifBlank { id }, n.optString("file_type"), n.optString("source_file"),
                    n.optString("source_location"), area)
                n.optString("community_name").takeIf { it.isNotBlank() }?.let { names.putIfAbsent(area, it) }
            }
            val links = HashMap<String, MutableList<Link>>()
            val arr = g.optJSONArray("links") ?: g.optJSONArray("edges")
            val seen = HashSet<String>()
            for (i in 0 until (arr?.length() ?: 0)) {
                val l = arr!!.getJSONObject(i)
                val s = l.optString("source")
                val t = l.optString("target")
                val rel = l.optString("relation", "links").replace('_', ' ')
                if (s == t || s !in parts || t !in parts || !seen.add("$s>$t>$rel")) continue
                links.getOrPut(s) { mutableListOf() }.add(Link(t, rel, true))
                links.getOrPut(t) { mutableListOf() }.add(Link(s, rel, false))
            }
            val byArea = parts.values.groupBy { it.area }
            val areas = byArea.map { (id, members) ->
                val ids = members.map { it.id }.sortedByDescending { links[it]?.size ?: 0 }
                // Graphify names an area after its main file; a name like "_" says nothing: use its biggest part
                val name = names[id]?.takeIf { it.trim().length > 1 } ?: parts[ids.first()]?.label ?: "Area ${id + 1}"
                Area(id, name, ids)
            }.sortedByDescending { it.parts.size }
            return ProjectMap(parts, links, areas)
        }
    }
}

/** Where the drill-down is: the project, an area, or a part. */
sealed interface Spot {
    data object Project : Spot
    data class InArea(val area: Int) : Spot
    data class OnPart(val id: String) : Spot
}

/** A bubble on the diagram: a centre or a neighbour. */
private class Bubble(val key: String, val label: String, val color: Color, val weight: Float, val tone: Int = 0)

private const val RING = 12

private fun areaColor(c: MochiColors, area: Int): Color {
    val light = listOf(c.blueInk, c.greenInk, c.violetInk, c.amberInk, c.danger, Color(0xFF0E7490), Color(0xFFA21CAF), Color(0xFF4D7C0F))
    return light[Math.floorMod(area, light.size)]
}

private fun plainType(t: String) = when (t) {
    "code" -> "code"
    "document" -> "document"
    "concept" -> "idea"
    "rationale" -> "reason"
    else -> t.ifBlank { "part" }
}

/** A short, plain explanation of a part from its links. */
private fun explain(m: ProjectMap, p: Part): String {
    val links = m.links[p.id].orEmpty()
    val uses = links.count { it.out }
    val usedBy = links.size - uses
    val area = m.areas.firstOrNull { it.id == p.area }?.name
    return buildString {
        append("A ${plainType(p.type)}")
        if (p.file.isNotBlank() && p.file != p.label) append(" in ${p.file.substringAfterLast('/')}")
        if (area != null) append(", part of the \"$area\" area")
        append(". It uses $uses ${if (uses == 1) "part" else "parts"} and $usedBy ${if (usedBy == 1) "part uses" else "parts use"} it.")
        if (usedBy >= 10) append(" Many things depend on it: change it with care.")
    }
}

@Composable
private fun MapScreen(repo: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var map by remember { mutableStateOf<ProjectMap?>(null) }
    var error by remember { mutableStateOf("") }
    val trail = remember { mutableStateListOf<Spot>(Spot.Project) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val spot = trail.last()

    LaunchedEffect(repo) {
        io(scope, { ProjectMap.parse(Mochi.graph(ctx, repo)) }, { error = "Couldn't load the map: ${it.message}" }) { map = it }
    }
    BackHandler(enabled = trail.size > 1 || searching) {
        if (searching) searching = false else trail.removeAt(trail.lastIndex)
    }
    fun go(s: Spot) { searching = false; if (trail.last() != s) trail.add(s) }

    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            RoundButton(Icons2.Back, "Back", c.field, c.ink, 44.dp) { if (trail.size > 1) trail.removeAt(trail.lastIndex) else onBack() }
            Column(Modifier.weight(1f)) {
                Text(repo, style = Type.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                map?.let { Text("${it.parts.size} parts · ${it.areas.size} areas", style = Type.small, color = c.muted) }
            }
            RoundButton(Icons2.Search, "Find a part", c.field, c.ink, 44.dp) { searching = !searching }
        }
        val m = map
        if (m == null) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Mascot(Modifier.size(96.dp), mood = if (error.isEmpty()) Mood.Thinking else Mood.Sad)
                Text(if (error.isEmpty()) "Reading the map…" else error, style = Type.body, color = c.muted)
            }
            return@Column
        }
        // the path so far: tap a step to go back to it
        if (trail.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = Space.l),
            horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            items(trail.indices.toList()) { i ->
                val s = trail[i]
                val name = when (s) {
                    Spot.Project -> repo
                    is Spot.InArea -> m.areas.firstOrNull { it.id == s.area }?.name ?: "Area"
                    is Spot.OnPart -> m.label(s.id)
                }
                Text((if (i > 0) "› " else "") + name, style = Type.small.copy(fontWeight = if (i == trail.lastIndex) Type.strong else null),
                    color = if (i == trail.lastIndex) c.ink else c.muted, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                        while (trail.size > i + 1) trail.removeAt(trail.lastIndex)
                    }.padding(vertical = Space.xs, horizontal = 2.dp))
            }
        }
        if (searching) {
            Field(query, { query = it }, "Find a part (e.g. Navbar, auth, api)", Modifier.fillMaxWidth().padding(horizontal = Space.l),
                singleLine = true)
            val hits = remember(query, m) {
                if (query.length < 2) emptyList() else m.parts.values.filter { it.label.contains(query, ignoreCase = true) }
                    .sortedByDescending { m.degree(it.id) }.take(40)
            }
            LazyColumn(contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                items(hits) { p -> PartRow(m, p.id, null) { go(Spot.OnPart(p.id)) } }
            }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            when (spot) {
                Spot.Project -> {
                    val shown = m.areas.take(RING)
                    item {
                        Diagram(Bubble("project", repo, c.ink, 1f),
                            shown.map { Bubble("a${it.id}", it.name, areaColor(c, it.id), it.parts.size.toFloat()) },
                            emptyList()) { key -> key.removePrefix("a").toIntOrNull()?.let { go(Spot.InArea(it)) } }
                    }
                    item { Hint("Each bubble is an area of the project (bigger = more parts). Tap one to look inside.") }
                    item { Section("All ${m.areas.size} areas") }
                    items(m.areas) { a ->
                        Row(Modifier.padding(horizontal = Space.l).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card)
                            .clickable { go(Spot.InArea(a.id)) }.padding(Space.l), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(areaColor(c, a.id)))
                            Column(Modifier.weight(1f)) {
                                Text(a.name, style = Type.body.copy(fontWeight = Type.strong), color = c.ink, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text("${a.parts.size} parts · key: " + a.parts.take(3).joinToString(", ") { m.label(it) },
                                    style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                is Spot.InArea -> {
                    val area = m.areas.firstOrNull { it.id == spot.area }
                    if (area != null) {
                        val key = area.parts.take(RING)
                        val inRing = key.toSet()
                        val ties = key.flatMap { id -> m.links[id].orEmpty().filter { it.out && it.other in inRing }.map { id to it.other } }
                        item {
                            Diagram(Bubble("area", area.name, areaColor(c, area.id), 1f),
                                key.map { Bubble(it, m.label(it), areaColor(c, area.id), m.degree(it).toFloat()) }, ties) { id ->
                                go(Spot.OnPart(id))
                            }
                        }
                        item { Hint("The ${key.size} most connected parts of this area, and how they link. Tap one for its page.") }
                        item { Section("All ${area.parts.size} parts") }
                        items(area.parts) { id -> PartRow(m, id, null) { go(Spot.OnPart(id)) } }
                    }
                }
                is Spot.OnPart -> {
                    val p = m.parts[spot.id]
                    if (p != null) {
                        val links = m.links[p.id].orEmpty()
                        val ring = links.distinctBy { it.other }.sortedByDescending { m.degree(it.other) }.take(RING)
                        item {
                            Diagram(Bubble(p.id, p.label, areaColor(c, p.area), 1f),
                                ring.map { Bubble(it.other, m.label(it.other), if (it.out) c.blueInk else c.greenInk,
                                    m.degree(it.other).toFloat(), if (it.out) 1 else 2) }, emptyList()) { id -> go(Spot.OnPart(id)) }
                        }
                        item {
                            Column(Modifier.padding(horizontal = Space.l).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card)
                                .padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                                Text(p.label, style = Type.title, color = c.ink)
                                Text(explain(m, p), style = Type.body, color = c.ink)
                                if (p.file.isNotBlank()) Text(p.file + if (p.where.isNotBlank()) " · ${p.where}" else "",
                                    style = Type.small, color = c.muted)
                                m.areas.firstOrNull { it.id == p.area }?.let { a ->
                                    Text("Area: ${a.name} ›", style = Type.small, color = c.blueInk,
                                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { go(Spot.InArea(a.id)) }
                                            .padding(vertical = Space.xs))
                                }
                            }
                        }
                        item { Hint("Blue: what it uses. Green: what uses it. Tap any to open its page.") }
                        val uses = links.filter { it.out }
                        val usedBy = links.filter { !it.out }
                        if (uses.isNotEmpty()) {
                            item { Section("Uses (${uses.size})") }
                            items(uses.sortedByDescending { m.degree(it.other) }) { l -> PartRow(m, l.other, l.rel, c.blueInk) { go(Spot.OnPart(l.other)) } }
                        }
                        if (usedBy.isNotEmpty()) {
                            item { Section("Used by (${usedBy.size})") }
                            items(usedBy.sortedByDescending { m.degree(it.other) }) { l -> PartRow(m, l.other, l.rel, c.greenInk) { go(Spot.OnPart(l.other)) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(text: String) = Box(Modifier.padding(horizontal = Space.xl)) { Label(text) }

@Composable
private fun Hint(text: String) {
    val c = LocalMochi.current
    Text(text, style = Type.small, color = c.muted, modifier = Modifier.padding(horizontal = Space.xl))
}

@Composable
private fun PartRow(m: ProjectMap, id: String, rel: String?, dot: Color? = null, onClick: () -> Unit) {
    val c = LocalMochi.current
    val p = m.parts[id]
    Row(Modifier.padding(horizontal = Space.l).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)
        .clickable(onClick = onClick).padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(dot ?: areaColor(c, p?.area ?: 0)))
        Column(Modifier.weight(1f)) {
            Text(m.label(id), style = Type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(rel, p?.file?.substringAfterLast('/')?.takeIf { it.isNotBlank() }, "${m.degree(id)} links")
                .joinToString(" · "), style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The diagram: [centre] in the middle, [ring] (≤ 12) evenly around it, spokes from the centre, and [ties] (pairs of
 * ring keys) as thin lines. Fixed positions: nothing moves unless pinched or dragged. Tap a ring bubble to open it.
 */
@Composable
private fun Diagram(centre: Bubble, ring: List<Bubble>, ties: List<Pair<String, String>>, onTap: (String) -> Unit) {
    val c = LocalMochi.current
    val measurer = rememberTextMeasurer()
    var zoom by remember(centre.key) { mutableStateOf(1f) }
    var pan by remember(centre.key) { mutableStateOf(Offset.Zero) }
    val maxW = (ring.maxOfOrNull { it.weight } ?: 1f).coerceAtLeast(1f)
    Box(Modifier.padding(horizontal = Space.l).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(c.card)) {
        Canvas(Modifier.fillMaxSize()
            .pointerInput(centre.key) {
                detectTransformGestures { _, p, z, _ -> zoom = (zoom * z).coerceIn(0.6f, 3f); pan += p }
            }
            .pointerInput(centre.key, ring.size) {
                detectTapGestures { tap ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val centreAt = Offset(w / 2, h / 2)
                    val radius = minOf(w, h) * 0.34f
                    ring.forEachIndexed { i, b ->
                        val pos = centreAt + polar(radius, i, ring.size)
                        val screen = (pos - centreAt) * zoom + centreAt + pan
                        val r = bubbleRadius(b.weight, maxW, minOf(w, h)) * zoom
                        if (hypot(tap.x - screen.x, tap.y - screen.y) <= r + 24f) { onTap(b.key); return@detectTapGestures }
                    }
                }
            }) {
            val centreAt = Offset(size.width / 2, size.height / 2)
            val radius = minOf(size.width, size.height) * 0.34f
            fun place(p: Offset) = (p - centreAt) * zoom + centreAt + pan
            val at = ring.mapIndexed { i, b -> b.key to place(centreAt + polar(radius, i, ring.size)) }.toMap()
            // links: spokes, then ties between ring parts
            ring.forEach { b ->
                val p = at.getValue(b.key)
                drawLine(if (b.tone == 0) c.muted.copy(alpha = 0.35f) else b.color.copy(alpha = 0.55f), place(centreAt), p,
                    strokeWidth = if (b.tone == 0) 2f else 3f)
            }
            ties.forEach { (a, b) -> if (a in at && b in at) drawLine(c.muted.copy(alpha = 0.25f), at.getValue(a), at.getValue(b), 1.5f) }
            ring.forEach { b -> bubble(measurer, c, at.getValue(b.key), bubbleRadius(b.weight, maxW, minOf(size.width, size.height)) * zoom, b, false) }
            bubble(measurer, c, place(centreAt), minOf(size.width, size.height) * 0.11f * zoom, centre, true)
        }
    }
}

private fun polar(radius: Float, i: Int, n: Int): Offset {
    val a = -PI / 2 + 2 * PI * i / maxOf(n, 1)
    return Offset((radius * cos(a)).toFloat(), (radius * sin(a)).toFloat())
}

private fun bubbleRadius(weight: Float, maxW: Float, side: Float) =
    side * (0.035f + 0.045f * sqrt(weight / maxW))

private fun androidx.compose.ui.graphics.drawscope.DrawScope.bubble(measurer: TextMeasurer, c: MochiColors, at: Offset,
                                                                  r: Float, b: Bubble, centre: Boolean) {
    drawCircle(if (centre) b.color else b.color.copy(alpha = 0.18f), r, at)
    drawCircle(b.color, r, at, style = Stroke(if (centre) 0f else 3f))
    val maxW = if (centre) r * 1.8f else r * 2.6f + 60f
    val text = measurer.measure(b.label, Type.small.copy(color = if (centre) c.bg else c.ink, fontSize = if (centre) 13.sp else 12.sp,
        fontWeight = if (centre) Type.strong else null), maxLines = 2, overflow = TextOverflow.Ellipsis,
        constraints = Constraints(maxWidth = maxW.toInt().coerceAtLeast(40)))
    val top = if (centre) at.y - text.size.height / 2 else at.y + r + 4f
    if (!centre) drawRect(c.card.copy(alpha = 0.85f), Offset(at.x - text.size.width / 2 - 4, top - 1),
        androidx.compose.ui.geometry.Size(text.size.width + 8f, text.size.height + 2f))
    drawText(text, topLeft = Offset(at.x - text.size.width / 2, top))
}
