package com.krow.ui

import android.app.Activity
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Meridian palette — warm paper / ink, terracotta accent.
 * No neon, no purple glow, no electric blues.
 */
@Immutable
class Pal(
    val dark: Boolean, val bg: Color, val side: Color, val surface: Color, val raised: Color, val sunk: Color,
    val ink: Color, val muted: Color, val faint: Color, val line: Color, val line2: Color,
    val accent: Color, val accentInk: Color, val ok: Color, val bad: Color,
    val shadow: Color,
) {
    val accentSoft get() = accent.copy(alpha = if (dark) .14f else .10f)
    val okBg get() = ok.copy(alpha = .14f)
    val badBg get() = bad.copy(alpha = .12f)
    val threadMax: Dp get() = 780.dp
}

val DarkPal = Pal(
    dark = true,
    bg = Color(0xFF151513), side = Color(0xFF0F0F0E), surface = Color(0xFF1B1B19),
    raised = Color(0xFF242421), sunk = Color(0xFF121211),
    ink = Color(0xFFE9E8E2), muted = Color(0xFF8F8D85), faint = Color(0xFF65635C),
    line = Color(0xFF2A2A27), line2 = Color(0xFF3A3935),
    accent = Color(0xFFDC6A44), accentInk = Color(0xFF1A0E09),
    ok = Color(0xFF7BB38A), bad = Color(0xFFE3877B),
    shadow = Color(0x99000000),
)

val LightPal = Pal(
    dark = false,
    bg = Color(0xFFF6F5F1), side = Color(0xFFECEAE5), surface = Color(0xFFFCFBF9),
    raised = Color(0xFFFFFFFF), sunk = Color(0xFFEFEDE8),
    ink = Color(0xFF25241F), muted = Color(0xFF75736A), faint = Color(0xFFA3A097),
    line = Color(0xFFDEDCD4), line2 = Color(0xFFCBC8BE),
    accent = Color(0xFFC4502C), accentInk = Color(0xFFFFFFFF),
    ok = Color(0xFF4A7857), bad = Color(0xFFA8453B),
    shadow = Color(0x28000000),
)

val LocalPal = staticCompositionLocalOf { DarkPal }
val Sans = FontFamily.SansSerif
val Read = FontFamily.Serif
val Mono = FontFamily.Monospace

/** Soft spring for UI entrances / send button — ChatGPT-like, not bouncy neon. */
fun <T> krowSpring() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

@Composable
fun KrowTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val p = if (dark) DarkPal else LightPal
    val cs = if (dark) darkColorScheme(
        primary = p.accent, onPrimary = p.accentInk, background = p.bg, surface = p.surface,
        onSurface = p.ink, onBackground = p.ink, secondary = p.muted, tertiary = p.ok,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = p.accentInk, background = p.bg, surface = p.surface,
        onSurface = p.ink, onBackground = p.ink, secondary = p.muted, tertiary = p.ok,
    )
    val view = LocalView.current
    SideEffect {
        val w = (view.context as Activity).window
        w.statusBarColor = p.bg.toArgb()
        w.navigationBarColor = p.bg.toArgb()
        WindowCompat.getInsetsController(w, view).let {
            it.isAppearanceLightStatusBars = !dark
            it.isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalPal provides p) { MaterialTheme(colorScheme = cs, content = content) }
}
