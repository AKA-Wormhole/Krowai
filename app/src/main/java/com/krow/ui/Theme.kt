package com.krow.ui

import android.app.Activity
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import com.krow.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Krow palette — clean Google-style neutrals with a blue accent.
 */
@Immutable
class Pal(
    val dark: Boolean, val bg: Color, val side: Color, val surface: Color, val raised: Color, val sunk: Color,
    val ink: Color, val muted: Color, val faint: Color, val line: Color, val line2: Color,
    val accent: Color, val accentInk: Color, val ok: Color, val bad: Color,
    val shadow: Color,
    val accent2: Color = accent,
) {
    val accentSoft get() = accent.copy(alpha = if (dark) .14f else .10f)
    val okBg get() = ok.copy(alpha = .14f)
    val badBg get() = bad.copy(alpha = .12f)
    val threadMax: Dp get() = 780.dp
}

val DarkPal = Pal(
    dark = true,
    bg = Color(0xFF1A1918), side = Color(0xFF1A1918), surface = Color(0xFF232220),
    raised = Color(0xFF302F2C), sunk = Color(0xFF151413),
    ink = Color(0xFFF2EFE8), muted = Color(0xFFB0ABA0), faint = Color(0xFF85817A),
    line = Color(0xFF2E2D2A), line2 = Color(0xFF3F3D39),
    accent = Color(0xFFE07B57), accentInk = Color(0xFF1F0F08),
    ok = Color(0xFF6DBE86), bad = Color(0xFFF0867A),
    shadow = Color(0x99000000),
    accent2 = Color(0xFFF0A35E),
)

val LightPal = Pal(
    dark = false,
    bg = Color(0xFFFAF9F5), side = Color(0xFFFAF9F5), surface = Color(0xFFFFFFFF),
    raised = Color(0xFFFFFFFF), sunk = Color(0xFFF1EFE7),
    ink = Color(0xFF141413), muted = Color(0xFF5F5D57), faint = Color(0xFF8F8B82),
    line = Color(0xFFE8E4D9), line2 = Color(0xFFD8D3C5),
    accent = Color(0xFFCC5F3C), accentInk = Color(0xFFFFFFFF),
    ok = Color(0xFF2E8B57), bad = Color(0xFFB3261E),
    shadow = Color(0x26141413),
    accent2 = Color(0xFFE08A3C),
)

/** Composer / input surface and the user's message bubble. */
val Pal.field: Color get() = if (dark) Color(0xFF232220) else Color(0xFFFFFFFF)
val Pal.bubble: Color get() = if (dark) Color(0xFF302F2C) else Color(0xFFEFEBDF)

/** Inline-code chip: soft blue on a faint tint. */
val Pal.codeInk: Color get() = if (dark) Color(0xFF8DB6F2) else Color(0xFF2B5FB8)
val Pal.codeBg: Color get() = if (dark) Color(0xFF222A36) else Color(0xFFE8EEF9)

/** Ember gradient (coral to amber) used for the logo, primary buttons and the send button. */
val Pal.brand: Brush get() = Brush.linearGradient(listOf(accent, accent2))

/** Selectable accent colours: gradient start/end for dark and light mode. */
class AccentSet(val key: String, val label: String, val dA: Color, val dB: Color, val dInk: Color, val lA: Color, val lB: Color)

val ACCENTS = listOf(
    AccentSet("emerald", "Emerald", Color(0xFF34D399), Color(0xFF2DD4BF), Color(0xFF04201A), Color(0xFF0F9D74), Color(0xFF0E8FA0)),
    AccentSet("ember", "Ember", Color(0xFFE07B57), Color(0xFFF0A35E), Color(0xFF1F0F08), Color(0xFFCC5F3C), Color(0xFFE08A3C)),
    AccentSet("blue", "Ocean", Color(0xFF6AA8FF), Color(0xFF8E7CFF), Color(0xFF07132B), Color(0xFF2F6FE4), Color(0xFF6B4FE0)),
    AccentSet("rose", "Rose", Color(0xFFFF7AA8), Color(0xFFE56BE0), Color(0xFF2A0716), Color(0xFFD6336C), Color(0xFFB93FC4)),
    AccentSet("gold", "Gold", Color(0xFFF5C451), Color(0xFFA8D84F), Color(0xFF241A02), Color(0xFFA87400), Color(0xFF5F8A12)),
    AccentSet("violet", "Violet", Color(0xFFA78BFA), Color(0xFFF472B6), Color(0xFF150A33), Color(0xFF7C3AED), Color(0xFFDB2777)),
)

fun Pal.withAccent(set: AccentSet): Pal = Pal(
    dark, bg, side, surface, raised, sunk, ink, muted, faint, line, line2,
    if (dark) set.dA else set.lA, if (dark) set.dInk else Color.White, ok, bad, shadow,
    accent2 = if (dark) set.dB else set.lB,
)

val LocalPal = staticCompositionLocalOf { DarkPal }
/** Bundled fonts so the system font setting can never replace Krow's typography. */
val Sans = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_bold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)
val Read = FontFamily(
    Font(R.font.lora_regular, FontWeight.Normal),
    Font(R.font.lora_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.lora_medium, FontWeight.Medium),
    Font(R.font.lora_semibold, FontWeight.SemiBold),
    Font(R.font.lora_semibold, FontWeight.Bold),
)
val Mono = FontFamily(
    Font(R.font.krow_mono_regular, FontWeight.Normal),
    Font(R.font.krow_mono_regular, FontWeight.Medium),
    Font(R.font.krow_mono_bold, FontWeight.SemiBold),
    Font(R.font.krow_mono_bold, FontWeight.Bold),
)

/** Soft spring for UI entrances / send button — ChatGPT-like, not bouncy neon. */
fun <T> krowSpring() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

@Composable
fun KrowTheme(mode: String, accent: String = "emerald", content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val p = (if (dark) DarkPal else LightPal).withAccent(ACCENTS.firstOrNull { it.key == accent } ?: ACCENTS.first())
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
    // Keep layouts stable: follow the system text size, but only within a sane range.
    val d = LocalDensity.current
    val density = Density(d.density, fontScale = d.fontScale.coerceIn(0.9f, 1.15f))
    CompositionLocalProvider(LocalPal provides p, LocalDensity provides density) { MaterialTheme(colorScheme = cs, content = content) }
}
