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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/** What Mochi is doing or feeling: it shows on its face. */
enum class Mood { Idle, Listening, Thinking, Speaking, Sleepy, Happy, Surprised, Sad }

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
private val bigSmilePath = PathParser().parsePathString("M50 71 Q 60 84 70 71").toPath()
private val frownPath = PathParser().parsePathString("M54 78 Q 60 72 66 78").toPath()
private val zPath = PathParser().parsePathString("M0 0 H8 L0 8 H8").toPath()
// an agent's badge, on Mochi's lower right: a pen (writer) or a shield (security)
private val penPath = PathParser().parsePathString("M84 96 L104 70 L110 75 L90 101 L82 103 Z").toPath()
private val shieldPath = PathParser().parsePathString(
    "M96 68 L110 73 C 110 88, 104 96, 96 101 C 88 96, 82 88, 82 73 Z").toPath()

/** How one of Mochi's specialist agents looks: Mochi's shape in their [color], with their [accessory]. */
data class AgentLook(val color: Color, val accessory: String)

/**
 * Mochi, the rice-cake mascot: breathes and blinks; [mood] moves its eyes and mouth (talks while [Mood.Speaking]).
 * A tap makes it hop (and calls [onTap]).
 */
@Composable
fun Mascot(modifier: Modifier = Modifier, mood: Mood = Mood.Idle, look: AgentLook? = null, label: String = "Mochi",
           onTap: (() -> Unit)? = null) {
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
    val drift by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "z")
    // a tap: a hop and a short happy or surprised face
    var reaction by remember { mutableStateOf<Mood?>(null) }

    val tap = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
        scope.launch {
            reaction = if (Random.nextInt(3) == 0) Mood.Surprised else Mood.Happy
            hop.snapTo(0f)
            hop.animateTo(1f, tween(750))
            hop.snapTo(0f)
            delay(900)
            reaction = null
        }
        onTap?.invoke()
    }
    Canvas(modifier.semantics { contentDescription = label }.then(if (onTap != null) tap else Modifier)) {
        val s = minOf(size.width / VIEW_W, size.height / VIEW_H)
        val ox = (size.width - VIEW_W * s) / 2
        val oy = (size.height - VIEW_H * s) / 2
        translate(ox, oy) {
            scale(s, s, pivot = Offset.Zero) { drawMochi(reaction ?: mood, breathe, blink, talk, bob, hop.value, drift, look) }
        }
    }
}

private fun DrawScope.drawMochi(mood: Mood, breathe: Float, blink: Float, talk: Float, bob: Float, hop: Float, drift: Float,
                                look: AgentLook? = null) {
    val body = look?.let { androidx.compose.ui.graphics.lerp(BODY, it.color, 0.32f) } ?: BODY
    val leaf = look?.color ?: LEAF
    // the hop: squash, jump, land (same curve as the website)
    val (jumpY, sx, sy) = hopShape(hop)
    // shadow shrinks while in the air
    val air = (-jumpY / 46f).coerceIn(0f, 1f)
    drawOval(INK.copy(alpha = 0.14f - 0.08f * air), topLeft = Offset(60f - 34f * (1 - 0.45f * air), 100f),
        size = Size(68f * (1 - 0.45f * air), 10f))
    // sleepy: slower, deeper breaths (half speed of the loop) and a little lower
    val br = if (mood == Mood.Sleepy) (breathe + 1f) / 2f else breathe
    val breatheX = 1f + (if (mood == Mood.Sleepy) 0.04f else 0.025f) * br
    val breatheY = 1f - (if (mood == Mood.Sleepy) 0.05f else 0.03f) * br
    val lift = if (mood == Mood.Thinking) bob * 1.5f else 0f
    translate(0f, jumpY + lift) {
        scale(sx * breatheX, sy * breatheY, pivot = Offset(60f, 101f)) {
            drawPath(bodyPath, body)
            drawPath(bodyPath, INK, style = Stroke(3.2f))
            drawPath(highlightPath, Color.White.copy(alpha = 0.9f), style = Stroke(4f, cap = StrokeCap.Round))
            drawPath(leafPath, leaf)
            drawPath(leafPath, INK, style = Stroke(2.6f, join = StrokeJoin.Round))
            drawPath(veinPath, INK, style = Stroke(1.6f, cap = StrokeCap.Round))
            val shy = mood == Mood.Listening || mood == Mood.Happy
            val cheekW = if (shy) 18f else 15f
            val cheekH = if (shy) 10.4f else 8.4f
            val cheekA = if (shy) 1f else 0.75f
            drawOval(CHEEK.copy(alpha = cheekA), Offset(35f - cheekW / 2, 75f - cheekH / 2), Size(cheekW, cheekH))
            drawOval(CHEEK.copy(alpha = cheekA), Offset(85f - cheekW / 2, 75f - cheekH / 2), Size(cheekW, cheekH))
            val look = when (mood) {
                Mood.Thinking -> Offset(-2f, -2f)
                Mood.Listening -> Offset(0f, 1f)
                Mood.Sad -> Offset(0f, 2f)
                else -> Offset.Zero
            }
            for (cx in floatArrayOf(46f, 74f)) {
                val x = cx + look.x
                val y = 62f + look.y
                when (mood) {
                    // closed eyes: a soft downward curve
                    Mood.Sleepy -> drawArc(INK, 0f, 180f, false, Offset(x - 5f, y - 3f), Size(10f, 6f),
                        style = Stroke(2.4f, cap = StrokeCap.Round))
                    // happy eyes: ^ ^
                    Mood.Happy -> drawArc(INK, 180f, 180f, false, Offset(x - 5f, y - 2f), Size(10f, 8f),
                        style = Stroke(2.6f, cap = StrokeCap.Round))
                    Mood.Surprised -> {
                        drawCircle(INK, 6f, Offset(x, y))
                        drawCircle(Color.White, 2f, Offset(x + 2f, y - 2.4f))
                    }
                    else -> {
                        val eyeH = 12.4f * blink * (if (mood == Mood.Sad) 0.75f else 1f)
                        drawOval(INK, Offset(x - 4.6f, y - eyeH / 2), Size(9.2f, eyeH))
                        if (blink > 0.5f) drawCircle(Color.White, 1.6f, Offset(x + 1.6f, y - 2.4f))
                    }
                }
            }
            when (mood) {
                Mood.Speaking -> {
                    val open = 2f + 5f * talk
                    drawOval(INK, Offset(55f, 73f - open / 2), Size(10f, open))
                }
                Mood.Happy -> drawPath(bigSmilePath, INK, style = Stroke(2.8f, cap = StrokeCap.Round))
                Mood.Surprised -> drawOval(INK, Offset(56f, 71f), Size(8f, 9f))
                Mood.Sad -> {
                    drawPath(frownPath, INK, style = Stroke(2.6f, cap = StrokeCap.Round))
                    drawOval(Color(0xFF8FB2FF), Offset(78f, 70f + 6f * drift), Size(4f, 6f)) // a tear
                }
                Mood.Sleepy -> drawOval(INK, Offset(57f, 73f - (1.5f + 1.5f * br)), Size(6f, 3f + 3f * br)) // a soft snore
                else -> drawPath(smilePath, INK, style = Stroke(2.6f, cap = StrokeCap.Round))
            }
            when (look?.accessory) {
                "pen" -> {
                    drawPath(penPath, look.color)
                    drawPath(penPath, INK, style = Stroke(2.2f, join = StrokeJoin.Round))
                }
                "shield" -> {
                    drawPath(shieldPath, look.color)
                    drawPath(shieldPath, INK, style = Stroke(2.2f, join = StrokeJoin.Round))
                    drawLine(Color.White, Offset(91f, 82f), Offset(95f, 87f), 2.4f, StrokeCap.Round)
                    drawLine(Color.White, Offset(95f, 87f), Offset(102f, 78f), 2.4f, StrokeCap.Round)
                }
            }
        }
    }
    if (mood == Mood.Sleepy) {
        // z z z drifting up from Mochi's head
        for (k in 0..2) {
            val t = (drift + k / 3f) % 1f
            val zs = 0.6f + 0.6f * t
            translate(92f + 10f * t, 30f - 26f * t) {
                scale(zs, zs, pivot = Offset.Zero) { drawPath(zPath, INK.copy(alpha = 1f - t), style = Stroke(1.8f)) }
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
