package app.mochi.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Line icons drawn like the design (2 px stroke, round ends), so no icon library is needed. */
object Icons2 {
    private fun line(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round, pathBuilder = block).build()

    val Mic = line("mic") {
        moveTo(12f, 3f); arcTo(3f, 3f, 0f, false, true, 15f, 6f); verticalLineTo(12f)
        arcTo(3f, 3f, 0f, false, true, 9f, 12f); verticalLineTo(6f); arcTo(3f, 3f, 0f, false, true, 12f, 3f); close()
        moveTo(19f, 11f); arcTo(7f, 7f, 0f, false, true, 5f, 11f); moveTo(12f, 18f); verticalLineTo(21f)
    }
    val Plus = line("plus") { moveTo(12f, 5f); verticalLineTo(19f); moveTo(5f, 12f); horizontalLineTo(19f) }
    val Back = line("back") { moveTo(15f, 6f); lineTo(9f, 12f); lineTo(15f, 18f) }
    val Send = line("send") { moveTo(12f, 19f); verticalLineTo(5f); moveTo(6f, 11f); lineTo(12f, 5f); lineTo(18f, 11f) }
    val Chat = line("chat") { moveTo(4f, 5f); horizontalLineTo(20f); verticalLineTo(16f); horizontalLineTo(9f); lineTo(4f, 20f); close() }
    val Clock = line("clock") {
        moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f)
        moveTo(12f, 7f); verticalLineTo(12f); lineTo(15f, 14f)
    }
    val Memory = line("memory") { moveTo(5f, 4f); horizontalLineTo(19f); verticalLineTo(20f); lineTo(12f, 16f); lineTo(5f, 20f); close() }
    val Bell = line("bell") {
        moveTo(6f, 8f); arcTo(6f, 6f, 0f, false, true, 18f, 8f); curveTo(18f, 15f, 21f, 16f, 21f, 16f)
        horizontalLineTo(3f); curveTo(3f, 16f, 6f, 15f, 6f, 8f); moveTo(10f, 20f); horizontalLineTo(14f)
    }
    val Search = line("search") {
        moveTo(18f, 11f); arcTo(7f, 7f, 0f, true, true, 4f, 11f); arcTo(7f, 7f, 0f, true, true, 18f, 11f)
        moveTo(16f, 16f); lineTo(20f, 20f)
    }
    val Trash = line("trash") {
        moveTo(4f, 7f); horizontalLineTo(20f); moveTo(9f, 7f); verticalLineTo(4f); horizontalLineTo(15f); verticalLineTo(7f)
        moveTo(6f, 7f); lineTo(7f, 20f); horizontalLineTo(17f); lineTo(18f, 7f)
    }
    val MicOff = line("mic-off") {
        moveTo(15f, 10f); verticalLineTo(6f); arcTo(3f, 3f, 0f, false, false, 9.4f, 4.6f)
        moveTo(9f, 9f); verticalLineTo(12f); arcTo(3f, 3f, 0f, false, false, 14.1f, 14.1f)
        moveTo(19f, 11f); arcTo(7f, 7f, 0f, false, true, 17.6f, 15.2f); moveTo(5f, 11f); arcTo(7f, 7f, 0f, false, false, 15.5f, 17.1f)
        moveTo(12f, 18f); verticalLineTo(21f); moveTo(3f, 3f); lineTo(21f, 21f)
    }
    val Speaker = line("speaker") {
        moveTo(4f, 9f); horizontalLineTo(8f); lineTo(13f, 5f); verticalLineTo(19f); lineTo(8f, 15f); horizontalLineTo(4f); close()
        moveTo(16.5f, 8.5f); arcTo(5f, 5f, 0f, false, true, 16.5f, 15.5f); moveTo(19f, 6f); arcTo(8.5f, 8.5f, 0f, false, true, 19f, 18f)
    }
    val Muted = line("muted") {
        moveTo(4f, 9f); horizontalLineTo(8f); lineTo(13f, 5f); verticalLineTo(19f); lineTo(8f, 15f); horizontalLineTo(4f); close()
        moveTo(16f, 9f); lineTo(22f, 15f); moveTo(22f, 9f); lineTo(16f, 15f)
    }
    val Snow = line("snow") {
        moveTo(12f, 3f); verticalLineTo(21f); moveTo(4.2f, 7.5f); lineTo(19.8f, 16.5f); moveTo(19.8f, 7.5f); lineTo(4.2f, 16.5f)
        moveTo(9.5f, 4.5f); lineTo(12f, 6.5f); lineTo(14.5f, 4.5f); moveTo(9.5f, 19.5f); lineTo(12f, 17.5f); lineTo(14.5f, 19.5f)
    }
    val Home = line("home") { moveTo(4f, 11f); lineTo(12f, 4f); lineTo(20f, 11f); verticalLineTo(20f); horizontalLineTo(4f); close() }
    val Shield = line("shield") {
        moveTo(12f, 3f); lineTo(20f, 7f); verticalLineTo(12f); curveTo(20f, 17f, 16.5f, 20f, 12f, 21f)
        curveTo(7.5f, 20f, 4f, 17f, 4f, 12f); verticalLineTo(7f); close(); moveTo(8.5f, 12f); lineTo(11f, 14.5f); lineTo(15.5f, 9.5f)
    }
    val Earpiece = line("earpiece") {
        moveTo(8f, 3f); horizontalLineTo(16f); arcTo(1f, 1f, 0f, false, true, 17f, 4f); verticalLineTo(20f)
        arcTo(1f, 1f, 0f, false, true, 16f, 21f); horizontalLineTo(8f); arcTo(1f, 1f, 0f, false, true, 7f, 20f)
        verticalLineTo(4f); arcTo(1f, 1f, 0f, false, true, 8f, 3f); close(); moveTo(10f, 6f); horizontalLineTo(14f)
    }
    val HangUp = line("hang-up") {
        moveTo(3f, 14f); curveTo(3f, 12f, 7f, 9f, 12f, 9f); curveTo(17f, 9f, 21f, 12f, 21f, 14f); lineTo(20f, 16f)
        lineTo(16f, 15f); lineTo(15.5f, 12.5f); curveTo(13.5f, 12f, 10.5f, 12f, 8.5f, 12.5f); lineTo(8f, 15f); lineTo(4f, 16f); close()
    }
    val Map = line("map") {
        moveTo(6f, 6f); lineTo(12f, 12f); lineTo(18f, 7f); moveTo(12f, 12f); lineTo(16f, 18f); moveTo(12f, 12f); lineTo(6f, 17f)
        moveTo(8f, 6f); arcTo(2f, 2f, 0f, true, true, 4f, 6f); arcTo(2f, 2f, 0f, true, true, 8f, 6f)
        moveTo(20f, 7f); arcTo(2f, 2f, 0f, true, true, 16f, 7f); arcTo(2f, 2f, 0f, true, true, 20f, 7f)
        moveTo(18f, 18f); arcTo(2f, 2f, 0f, true, true, 14f, 18f); arcTo(2f, 2f, 0f, true, true, 18f, 18f)
        moveTo(8f, 17f); arcTo(2f, 2f, 0f, true, true, 4f, 17f); arcTo(2f, 2f, 0f, true, true, 8f, 17f)
    }
    val Check = line("check") { moveTo(5f, 12f); lineTo(10f, 17f); lineTo(19f, 7f) }
    val Close = line("close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val Phone = line("phone") {
        moveTo(5f, 4f); horizontalLineTo(9f); lineTo(11f, 9f); lineTo(8.5f, 10.5f); curveTo(9.5f, 12.8f, 11.2f, 14.5f, 13.5f, 15.5f)
        lineTo(15f, 13f); lineTo(20f, 15f); verticalLineTo(19f); curveTo(20f, 20f, 19f, 21f, 18f, 21f)
        curveTo(10f, 20.5f, 3.5f, 14f, 3f, 6f); curveTo(3f, 5f, 4f, 4f, 5f, 4f); close()
    }
    val Settings = line("settings") {
        moveTo(15f, 12f); arcTo(3f, 3f, 0f, true, true, 9f, 12f); arcTo(3f, 3f, 0f, true, true, 15f, 12f)
        moveTo(12f, 2f); verticalLineTo(5f); moveTo(12f, 19f); verticalLineTo(22f); moveTo(2f, 12f); horizontalLineTo(5f)
        moveTo(19f, 12f); horizontalLineTo(22f); moveTo(5f, 5f); lineTo(7f, 7f); moveTo(17f, 17f); lineTo(19f, 19f)
        moveTo(5f, 19f); lineTo(7f, 17f); moveTo(17f, 7f); lineTo(19f, 5f)
    }
}

@Composable
fun LineIcon(icon: ImageVector, tint: Color, size: Dp = 20.dp, label: String? = null) =
    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(size))

/** A round icon button, 48 dp (thumb-sized). */
@Composable
fun RoundButton(icon: ImageVector, label: String, bg: Color, tint: Color, size: Dp = 48.dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(bg).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        LineIcon(icon, tint)
    }
}

/** The stop button shown while Mochi is replying. */
@Composable
fun StopButton(onClick: () -> Unit) {
    val c = LocalMochi.current
    Box(Modifier.size(48.dp).clip(CircleShape).background(c.coral).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "Stop reply" }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(14.dp).background(Color.White, RoundedCornerShape(3.dp)))
    }
}

/** A pill-shaped button: [filled] = ink on the theme's ground, else outlined. */
@Composable
fun Pill(text: String, filled: Boolean, modifier: Modifier = Modifier, height: Dp = 44.dp, onClick: () -> Unit) {
    val c = LocalMochi.current
    val shape = RoundedCornerShape(height / 2)
    Box(modifier.height(height).clip(shape)
        .then(if (filled) Modifier.background(c.ink) else Modifier.border(1.dp, c.line, shape))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = Space.l),
        contentAlignment = Alignment.Center) {
        Text(text, style = Type.body.copy(fontWeight = if (filled) Type.strong else null), color = if (filled) c.bg else c.ink)
    }
}

/** A colour-coded tile on the home screen. */
@Composable
fun Tile(bg: Color, modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(bg).clickable(onClick = onClick).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s)) { content() }
}

/** The rounded text field used for messages and search. */
@Composable
fun Field(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
          singleLine: Boolean = false, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val c = LocalMochi.current
    Row(modifier.defaultMinSize(minHeight = 48.dp).clip(RoundedCornerShape(24.dp)).background(c.field)
        .padding(start = Space.l, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).padding(vertical = Space.m)) {
            if (value.isEmpty()) Text(placeholder, style = Type.body, color = c.muted)
            BasicTextField(value, onChange, textStyle = Type.body.copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                singleLine = singleLine, maxLines = if (singleLine) 1 else 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder })
        }
        trailing?.invoke(this)
    }
}

/** A small section label. */
@Composable
fun Label(text: String) {
    val c = LocalMochi.current
    Text(text, style = Type.small.copy(fontWeight = Type.strong), color = c.muted,
        modifier = Modifier.padding(top = Space.s, bottom = Space.xs))
}

/**
 * Runs [block] only while the screen is visible: cancelled when the app goes to the background, started again when
 * it comes back. For refresh loops, so a hidden screen doesn't keep polling the server (battery, data).
 */
@Composable
fun WhileVisible(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, *keys) { owner.repeatOnLifecycle(Lifecycle.State.STARTED, block) }
}

/** Runs [work] off the main thread, then [done] (or [failed]) on it. */
fun <T> io(scope: CoroutineScope, work: () -> T, failed: (Throwable) -> Unit = {}, done: (T) -> Unit) {
    scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching(work) }
        r.onSuccess(done).onFailure(failed)
    }
}
