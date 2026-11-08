package app.mochi.phone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Mochi's look (mochi-phone/DESIGN.md): white or near-black ground, ink text, soft colour-coded tiles, one coral
 * accent for "Call Mochi". Follows the phone's light/dark setting. Four text sizes, two weights, an 8 dp grid.
 */
@Immutable
data class MochiColors(
    val bg: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val field: Color,
    val card: Color,
    val amberBg: Color, val amberInk: Color,
    val blueBg: Color, val blueInk: Color,
    val greenBg: Color, val greenInk: Color,
    val violetBg: Color, val violetInk: Color,
    val coral: Color,
    val danger: Color,
    val userBubble: Color, val onUserBubble: Color,
    val codeBg: Color,
    val dark: Boolean,
)

val LightColors = MochiColors(
    bg = Color(0xFFFFFFFF), ink = Color(0xFF111111), muted = Color(0xFF5F6368), line = Color(0xFFE3E5E8),
    field = Color(0xFFF2F3F5), card = Color(0xFFF7F7F8),
    amberBg = Color(0xFFFFF4E5), amberInk = Color(0xFF8A4B00),
    blueBg = Color(0xFFEAF1FF), blueInk = Color(0xFF2A55AE),
    greenBg = Color(0xFFEAF7EF), greenInk = Color(0xFF1E7A45),
    violetBg = Color(0xFFF3EEFF), violetInk = Color(0xFF5B3FC4),
    coral = Color(0xFFE8613C), danger = Color(0xFFB4472C),
    userBubble = Color(0xFF111111), onUserBubble = Color(0xFFFFFFFF),
    codeBg = Color(0xFFECEDEF), dark = false,
)

val DarkColors = MochiColors(
    bg = Color(0xFF111214), ink = Color(0xFFF1F2F4), muted = Color(0xFFA6ABB3), line = Color(0xFF2C2F35),
    field = Color(0xFF1E2024), card = Color(0xFF1A1C20),
    amberBg = Color(0xFF2E2416), amberInk = Color(0xFFF2B866),
    blueBg = Color(0xFF18233A), blueInk = Color(0xFF8FB2FF),
    greenBg = Color(0xFF15291E), greenInk = Color(0xFF7FD3A0),
    violetBg = Color(0xFF241C3A), violetInk = Color(0xFFBCA8FF),
    coral = Color(0xFFE8613C), danger = Color(0xFFE07A5F),
    userBubble = Color(0xFFF1F2F4), onUserBubble = Color(0xFF111214),
    codeBg = Color(0xFF26292E), dark = true,
)

val LocalMochi = staticCompositionLocalOf { LightColors }

/** The four text sizes (13 / 15 / 17 / 24) in two weights. */
object Type {
    val small = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    val title = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val display = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
    val strong = FontWeight.SemiBold
}

/** Spacing on the 8 dp grid. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

@Composable
fun MochiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(primary = c.ink, onPrimary = c.bg, background = c.bg, surface = c.bg, onBackground = c.ink,
            onSurface = c.ink, surfaceVariant = c.field, onSurfaceVariant = c.muted, outline = c.line, error = c.danger)
    } else {
        lightColorScheme(primary = c.ink, onPrimary = c.bg, background = c.bg, surface = c.bg, onBackground = c.ink,
            onSurface = c.ink, surfaceVariant = c.field, onSurfaceVariant = c.muted, outline = c.line, error = c.danger)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalMochi provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
