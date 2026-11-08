package app.mochi.phone.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch

/** What Mochi is doing: it shows on its face. */
enum class Mood { Idle, Listening, Thinking, Speaking }

private const val VIEW_W = 120f
private const val VIEW_H = 112f
private val INK = Color(0xFF2A2522)
private val BODY = Color(0xFFFFFAF1)
private val LEAF = Color(0xFFFFC53D)
private val CHEEK = Color(0xFFFFB4A2)

// The portfolio site's Mochi (portfolio-site client/src/pages/bento/mascot.jsx), same shapes, viewBox 0 0 120 112.
private val bodyPath = PathParser().parsePathString(
    "M14 86 C 14 46, 36 22, 60 22 C 84 22, 106 46, 106 86 C 106 97, 95 101, 60 101 C 25 101, 14 97, 14 86 Z").toPath()
private val highlightPath = PathParser().parsePathString("M32 46 C 38 36, 46 31, 54 29").toPath()
private val leafPath = PathParser().parsePathString("M60 23 C 58 12, 64 6, 74 4 C 74 14, 69 21, 60 23 Z").toPath()
private val veinPath = PathParser().parsePathString("M60 23 C 63 17, 67 12, 71 8").toPath()
private val smilePath = PathParser().parsePathString("M54 73 Q 60 79 66 73").toPath()

/**
 * Mochi, the rice-cake mascot: breathes and blinks; [mood] moves its eyes and mouth (talks while [Mood.Speaking]).
 * A tap makes it hop (and calls [onTap]).
 */
@Composable
fun Mascot(modifier: Modifier = Modifier, mood: Mood = Mood.Idle, onTap: (() -> Unit)? = null) {
    val loop = rememberInfiniteTransition(label = "mochi")
    val breathe by loop.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val blink by loop.animateFloat(1f, 1f, infiniteRepeatable(keyframes {
        durationMillis = 4200
        1f at 3860
        0.1f at 3990
        1f at 4120
    }), label = "blink")
    val talk by loop.animateFloat(0f, 1f,
        infiniteRepeatable(tween(260, easing = LinearEasing), RepeatMode.Reverse), label = "talk")
    val bob by loop.animateFloat(-1f, 1f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val hop = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val tap = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
        scope.launch {
            hop.snapTo(0f)
            hop.animateTo(1f, tween(750))
            hop.snapTo(0f)
        }
        onTap?.invoke()
    }
    Canvas(modifier.semantics { contentDescription = "Mochi" }.then(if (onTap != null) tap else Modifier)) {
        val s = minOf(size.width / VIEW_W, size.height / VIEW_H)
        val ox = (size.width - VIEW_W * s) / 2
        val oy = (size.height - VIEW_H * s) / 2
        translate(ox, oy) {
            scale(s, s, pivot = Offset.Zero) { drawMochi(mood, breathe, blink, talk, bob, hop.value) }
        }
    }
}

private fun DrawScope.drawMochi(mood: Mood, breathe: Float, blink: Float, talk: Float, bob: Float, hop: Float) {
    // the hop: squash, jump, land (same curve as the website)
    val (jumpY, sx, sy) = hopShape(hop)
    // shadow shrinks while in the air
    val air = (-jumpY / 46f).coerceIn(0f, 1f)
    drawOval(INK.copy(alpha = 0.14f - 0.08f * air), topLeft = Offset(60f - 34f * (1 - 0.45f * air), 100f),
        size = Size(68f * (1 - 0.45f * air), 10f))
    val breatheX = 1f + 0.025f * breathe
    val breatheY = 1f - 0.03f * breathe
    val lift = if (mood == Mood.Thinking) bob * 1.5f else 0f
    translate(0f, jumpY + lift) {
        scale(sx * breatheX, sy * breatheY, pivot = Offset(60f, 101f)) {
            drawPath(bodyPath, BODY)
            drawPath(bodyPath, INK, style = Stroke(3.2f))
            drawPath(highlightPath, Color.White.copy(alpha = 0.9f), style = Stroke(4f, cap = StrokeCap.Round))
            drawPath(leafPath, LEAF)
            drawPath(leafPath, INK, style = Stroke(2.6f, join = StrokeJoin.Round))
            drawPath(veinPath, INK, style = Stroke(1.6f, cap = StrokeCap.Round))
            val shy = mood == Mood.Listening
            val cheekW = if (shy) 18f else 15f
            val cheekH = if (shy) 10.4f else 8.4f
            val cheekA = if (shy) 1f else 0.75f
            drawOval(CHEEK.copy(alpha = cheekA), Offset(35f - cheekW / 2, 75f - cheekH / 2), Size(cheekW, cheekH))
            drawOval(CHEEK.copy(alpha = cheekA), Offset(85f - cheekW / 2, 75f - cheekH / 2), Size(cheekW, cheekH))
            // eyes look up while thinking, at you while listening
            val look = when (mood) {
                Mood.Thinking -> Offset(-2f, -2f)
                Mood.Listening -> Offset(0f, 1f)
                else -> Offset.Zero
            }
            val eyeH = 12.4f * blink
            for (cx in floatArrayOf(46f, 74f)) {
                drawOval(INK, Offset(cx - 4.6f + look.x, 62f - eyeH / 2 + look.y), Size(9.2f, eyeH))
                if (blink > 0.5f) drawCircle(Color.White, 1.6f, Offset(cx + 1.6f + look.x, 59.6f + look.y))
            }
            if (mood == Mood.Speaking) {
                // a small open mouth that moves while Mochi talks
                val open = 2f + 5f * talk
                drawOval(INK, Offset(55f, 73f - open / 2), Size(10f, open))
            } else {
                drawPath(smilePath, INK, style = Stroke(2.6f, cap = StrokeCap.Round))
            }
        }
    }
}

/** y offset and x/y scale for the hop at progress [t] (0..1). */
private fun hopShape(t: Float): Triple<Float, Float, Float> {
    if (t <= 0f || t >= 1f) return Triple(0f, 1f, 1f)
    val times = floatArrayOf(0f, 0.18f, 0.5f, 0.78f, 1f)
    val y = floatArrayOf(0f, 4f, -46f, 0f, 0f)
    val sx = floatArrayOf(1f, 1.14f, 0.92f, 1.12f, 1f)
    val sy = floatArrayOf(1f, 0.84f, 1.1f, 0.88f, 1f)
    val i = (1 until times.size).first { t <= times[it] }
    val f = (t - times[i - 1]) / (times[i] - times[i - 1])
    fun lerp(a: FloatArray) = a[i - 1] + (a[i] - a[i - 1]) * f
    return Triple(lerp(y), lerp(sx), lerp(sy))
}
