package app.mochi.phone

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mochi.phone.ui.Field
import app.mochi.phone.ui.Icons2
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.Pill
import app.mochi.phone.ui.RoundButton
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Type
import app.mochi.phone.ui.io
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** The design style catalogue (mochi-ring /draw/styles, built by mochi-draw-styles on the server) and its previews. */
object DrawStyles {
    data class Style(val id: String, val name: String, val group: String, val line: String, val colors: List<Color>,
                     val hasThumb: Boolean, val video: Boolean, val note: String)

    @Volatile private var cache: List<Style>? = null
    private val thumbs = ConcurrentHashMap<String, ImageBitmap>()

    /** Blocking. */
    fun load(c: Context): List<Style> = cache ?: Doing.get(c, "/draw/styles").optJSONArray("styles").let { a ->
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .filter { it.optBoolean("usable", true) }
            .map { o ->
                val colors = o.optJSONArray("colors")?.let { cs -> (0 until cs.length()).mapNotNull {
                    runCatching { Color(android.graphics.Color.parseColor(cs.optString(it))) }.getOrNull() } }.orEmpty()
                Style(o.optString("id"), o.optString("name"), o.optString("group"), o.optString("line"), colors,
                    o.optBoolean("has_thumb"), o.optBoolean("video", true), o.optString("note"))
            }
    }.also { if (it.isNotEmpty()) cache = it }

    /** A preview still (lemo styles have one; the rest show their colours). Blocking, cached. */
    fun thumb(c: Context, id: String): ImageBitmap? = thumbs[id] ?: runCatching {
        Mochi.http.newCall(Request.Builder().url("${Mochi.ringUrl(c)}/draw/thumb/$id")
            .header("Authorization", "Bearer ${Mochi.token(c)}").build()).execute().use { r ->
            val bytes = r.body?.bytes()
            if (!r.isSuccessful || bytes == null) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }.getOrNull()?.also { thumbs[id] = it }
}

/** What December can ask for. [designed]: Iris draws it, so a style is picked next. */
enum class VisualKind(val label: String, val hint: String, val designed: Boolean, val ask: String) {
    Photo("Your photo or video", "From this phone's gallery", false, ""),
    Screenshot("Screenshot", "Of the project the post is about", false, "a screenshot of the project it's about"),
    Image("Designed image", "One image drawn for this post", true, "one image designed for it"),
    Images("Several images", "2-5 images posted together", true, "several images (2-5) designed for it, posted together"),
    Carousel("Carousel", "Swipeable pages, 5-8 (a PDF)", true, "a carousel (PDF, 5-8 pages) designed from its points"),
    Chart("Chart", "Its real numbers, drawn", true, "a chart of its real numbers"),
    Video("Video", "A short animated video", true, "a short video"),
    Launch("Launch video", "A 15-25 s launch film of the project, from its code (brag)", true,
        "a launch video of the project, made by Iris with her brag skill (15-25 s)"),
}

/**
 * Add or change a post's visual in two steps: what kind, then (for a designed one) the style, with "Let Iris choose"
 * picked from the start. [onAsk] gets the kind, the style (null = Iris chooses), December's note and the voice-over
 * choice (video only).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VisualPicker(fit: String, why: String, onPhoto: () -> Unit,
                          onAsk: (VisualKind, DrawStyles.Style?, String, Boolean) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf<VisualKind?>(null) }
    var styles by remember { mutableStateOf<List<DrawStyles.Style>>(emptyList()) }
    var loadError by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("All") }
    var picked by remember { mutableStateOf<DrawStyles.Style?>(null) }
    var note by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        io(scope, { DrawStyles.load(ctx) }, { loadError = "Couldn't load the styles; Iris can still choose." }) { styles = it }
    }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.statusBarsPadding().padding(top = Space.m), containerColor = c.bg, dragHandle = null) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding().imePadding()
            .padding(horizontal = Space.l, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                if (kind != null) RoundButton(Icons2.Back, "Back", c.field, c.ink, 40.dp) { kind = null }
                Column(Modifier.weight(1f)) {
                    Text(kind?.label ?: "Add a visual", style = Type.display, color = c.ink)
                    Text(if (kind == null) "What should go with the post?" else "Pick a style, or let Iris choose",
                        style = Type.small, color = c.muted)
                }
                RoundButton(Icons2.Close, "Close", c.field, c.ink, 40.dp, onClose)
            }
            val k = kind
            if (k == null) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    VisualKind.entries.forEach { option ->
                        KindRow(option, if (option == VisualKind.Video && fit.isNotBlank())
                            (if (fit == "good") "Good fit: " else "Not ideal: ") + why else option.hint) {
                            when {
                                option == VisualKind.Photo -> { onClose(); onPhoto() }
                                !option.designed -> { onClose(); onAsk(option, null, "", false) }
                                else -> { kind = option; picked = null; group = "All" }
                            }
                        }
                    }
                }
            } else {
                val moving = k == VisualKind.Video || k == VisualKind.Launch
                val usable = styles.filter { !moving || it.video }
                val groups = listOf("All") + usable.map { it.group }.distinct()
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    items(groups) { g -> Pill(g, g == group, height = 36.dp) { group = g } }
                }
                LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f), contentPadding = PaddingValues(bottom = Space.s),
                    horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    item(span = { GridItemSpan(2) }) {
                        Choice(picked == null, Modifier.fillMaxWidth(), { picked = null }) {
                            Column(Modifier.padding(Space.m)) {
                                Text("Let Iris choose", style = Type.title, color = c.ink)
                                Text("She picks what fits the post (your bento look for your own work)",
                                    style = Type.small, color = c.muted)
                            }
                        }
                    }
                    if (loadError.isNotBlank()) item(span = { GridItemSpan(2) }) { Text(loadError, style = Type.small, color = c.muted) }
                    items(usable.filter { group == "All" || it.group == group }, key = { it.id }) { s ->
                        StyleCard(s, picked?.id == s.id) { picked = s }
                    }
                }
                if (moving) Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Pill(if (voice) "✓ Voice-over" else "Voice-over", voice, height = 36.dp) { voice = !voice }
                    Text(if (voice) "Narrated, music under it" else "Silent or music only", style = Type.small, color = c.muted)
                }
                Field(note, { note = it }, "Anything specific? (optional)", Modifier.fillMaxWidth())
                Pill("Ask for ${k.label.lowercase()}" + (picked?.let { " · ${it.name}" } ?: ""), true, Modifier.fillMaxWidth()) {
                    onClose(); onAsk(k, picked, note.trim(), voice)
                }
            }
        }
    }
}

@Composable
private fun KindRow(kind: VisualKind, hint: String, onClick: () -> Unit) {
    val c = LocalMochi.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = Space.l, vertical = Space.m)) {
        Text(kind.label, style = Type.title, color = c.ink)
        Text(hint, style = Type.small, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Choice(selected: Boolean, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    val c = LocalMochi.current
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.clip(shape).background(c.card).border(if (selected) 2.dp else 1.dp, if (selected) c.ink else c.line, shape)
        .clickable(role = Role.RadioButton, onClick = onClick)) { content() }
}

@Composable
private fun StyleCard(s: DrawStyles.Style, selected: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var image by remember(s.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(s.id) { if (s.hasThumb) io(scope, { DrawStyles.thumb(ctx, s.id) }) { image = it } }
    Choice(selected, Modifier.fillMaxWidth(), onClick) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(c.field)) {
                val img = image
                if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else if (s.colors.isNotEmpty()) Row(Modifier.fillMaxSize()) {
                    s.colors.forEach { col -> Box(Modifier.weight(1f).fillMaxHeight().background(col)) }
                }
            }
            Column(Modifier.padding(Space.s)) {
                Text(s.name, style = Type.small.copy(fontWeight = Type.strong), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.line, style = Type.small, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (s.note.isNotBlank()) Text(s.note, style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The request to Nadia for a visual (she briefs Iris and attaches what comes back). */
internal fun visualRequest(id: String, kind: VisualKind, style: DrawStyles.Style?, note: String): String =
    "December asks for a visual on LinkedIn draft $id (from Nadia's page in the app): ${kind.ask}. Style: " +
        (style?.let { "${it.id} (${it.name}): run mochi-draw style ${it.id}" }
            ?: "Iris chooses (December's work: ours/december-bento unless the subject asks for more)") + ". " +
        (if (note.isNotBlank()) "December's note: $note. " else "") +
        (if (kind.designed) "Brief Iris (Brief for the designer) with exactly this format and style, then attach what she makes: " +
            "images with mochi-linkedin edit $id --image <file> --alt \"...\" (one --image per file), a carousel with " +
            "mochi-linkedin document $id <file.pdf> --title \"...\", a video with mochi-linkedin video $id <mp4>. "
        else "Attach it with mochi-linkedin edit $id --image <file> --alt \"...\". ") +
        "Keep the text as it is."
