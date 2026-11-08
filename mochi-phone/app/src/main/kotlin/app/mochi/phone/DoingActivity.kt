package app.mochi.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mochi.phone.ui.Icons2
import app.mochi.phone.ui.LocalMochi
import app.mochi.phone.ui.Mascot
import app.mochi.phone.ui.MochiColors
import app.mochi.phone.ui.MochiTheme
import app.mochi.phone.ui.Mood
import app.mochi.phone.ui.RoundButton
import app.mochi.phone.ui.Space
import app.mochi.phone.ui.Type
import app.mochi.phone.ui.io
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * "What Mochi is doing": Mochi in the middle, its helpers around it (voice, Claude, memory, web, server, phone,
 * GitHub, and any helper agents) lighting up while they work, and the steps as a plain timeline. Opened by tapping
 * Mochi on a call or in a chat, or Home's "Mochi is working" strip.
 */
class DoingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MochiTheme { DoingScreen { finish() } } }
    }

    companion object {
        fun open(c: Context) = c.startActivity(Intent(c, DoingActivity::class.java))
    }
}

/** One helper around Mochi. */
private class Lane(val key: String, val label: String, val bg: Color, val ink: Color)

private fun lanes(c: MochiColors, helpers: List<String>) = listOf(
    Lane("voice", "Voice", c.amberBg, c.amberInk),
    Lane("brain", "Claude", c.blueBg, c.blueInk),
    Lane("memory", "Memory", c.violetBg, c.violetInk),
    Lane("web", "Web", c.greenBg, c.greenInk),
    Lane("server", "Server", c.field, c.ink),
    Lane("phone", "Phone", c.amberBg, c.amberInk),
    Lane("github", "GitHub", c.field, c.ink),
) + helpers.take(3).map { Lane("helper:$it", it.removePrefix("Helper: ").take(14), c.blueBg, c.blueInk) }

private fun laneOf(step: Doing.Step) = if (step.lane == "helper") "helper:" + step.who else step.lane

@Composable
private fun DoingScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalMochi.current
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var filter by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        io(scope, { Doing.backfill(ctx) }) {}
        while (true) { delay(1_000); now = System.currentTimeMillis() }
    }
    val steps = Doing.steps
    val active = Doing.activeLanes(now)
    val busy = Doing.busy(now)
    val helpers = steps.filter { it.lane == "helper" && now - it.at < 10 * 60_000 }.map { it.who }.distinct()
    val all = lanes(c, helpers)
    val last = steps.lastOrNull()
    val callSpeaking = Call.active && Call.said.lastOrNull()?.let { it.role != "user" && !it.final } == true
    val mood = when {
        callSpeaking -> Mood.Speaking
        busy -> Mood.Thinking
        last == null || now - last.at > 5 * 60_000 -> Mood.Sleepy
        else -> Mood.Happy
    }

    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons2.Back, "Back", c.field, c.ink, 44.dp, onBack)
            Column(Modifier.weight(1f).padding(horizontal = Space.m)) {
                Text("What Mochi is doing", style = Type.title, color = c.ink)
                Text(when {
                    busy && last != null -> "Working: ${last.text}"
                    last != null -> "Resting · last step " + DateUtils.getRelativeTimeSpanString(last.at)
                    else -> "Nothing yet. Steps show up here as Mochi works."
                }, style = Type.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Mochi and its helpers
        BoxWithConstraints(Modifier.padding(horizontal = Space.l).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp))
            .background(c.card), contentAlignment = Alignment.Center) {
            val side = maxWidth
            val ring = side * 0.36f
            Mascot(Modifier.size(side * 0.34f), mood = mood, onTap = {})
            all.forEachIndexed { i, lane ->
                val a = -PI / 2 + 2 * PI * i / all.size
                LaneBubble(lane, lane.key in active, filter == lane.key,
                    Modifier.offset(x = ring * cos(a).toFloat(), y = ring * sin(a).toFloat())) {
                    filter = if (filter == lane.key) null else lane.key
                }
            }
        }
        val shown = steps.filter { filter == null || laneOf(it) == filter }.asReversed()
        Text(if (filter == null) "Steps, newest first" else "Only: ${all.firstOrNull { it.key == filter }?.label} (tap it again for all)",
            style = Type.small, color = c.muted, modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.s))
        LazyColumn(contentPadding = PaddingValues(start = Space.l, end = Space.l, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (shown.isEmpty()) item {
                Text("Nothing here yet.", style = Type.body, color = c.muted, modifier = Modifier.padding(Space.l))
            }
            items(shown, key = { it.id }) { s ->
                val lane = all.firstOrNull { it.key == laneOf(s) } ?: Lane(s.lane, s.lane, c.field, c.ink)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).padding(Space.m),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(lane.ink))
                    Column(Modifier.weight(1f)) {
                        Text(s.text, style = Type.body, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(listOf(s.who, s.repo.takeIf { it.isNotBlank() }, DateUtils.getRelativeTimeSpanString(s.at).toString())
                            .filterNotNull().joinToString(" · "), style = Type.small, color = c.muted, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun LaneBubble(lane: Lane, on: Boolean, picked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalMochi.current
    val pulse by rememberInfiniteTransition(label = "lane").animateFloat(0.55f, 1f,
        infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val size: Dp = 64.dp
    Box(modifier.size(size).clip(CircleShape).background(if (on) lane.bg else c.field)
        .then(if (picked) Modifier.border(2.dp, c.ink, CircleShape) else if (on) Modifier.border(2.dp, lane.ink, CircleShape) else Modifier)
        .alpha(if (on) pulse else 0.55f).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(lane.label, style = Type.small.copy(fontWeight = if (on) Type.strong else null), color = if (on) lane.ink else c.muted,
            textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.widthIn(max = size - 8.dp))
    }
}
