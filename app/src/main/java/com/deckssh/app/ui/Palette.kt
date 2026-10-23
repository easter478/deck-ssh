package com.deckssh.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deckssh.app.R

/** One theme from the shared pack. Colours are ARGB longs so the generated table stays compact. */
class Palette(
    val id: String, val name: String, val desc: String, val ref: String, val dark: Boolean,
    bg: Long, surface: Long, surface2: Long, fg: Long, muted: Long, border: Long,
    primary: Long, onPrimary: Long, accent: Long, danger: Long, warn: Long,
) {
    val bg = Color(bg); val surface = Color(surface); val surface2 = Color(surface2)
    val fg = Color(fg); val muted = Color(muted); val border = Color(border)
    val primary = Color(primary); val onPrimary = Color(onPrimary); val accent = Color(accent)
    val danger = Color(danger); val warn = Color(warn)

    // Keyboard surfaces are derived so every theme gets a deck that matches it.
    val deck = if (dark) lerp(this.bg, Color.Black, 0.35f) else lerp(this.surface, Color.Black, 0.12f)
    val keyTop = if (dark) lerp(this.surface2, Color.White, 0.06f) else Color.White.copy(alpha = 0.92f).compositeOver(this.surface2)
    val keySkirt = if (dark) lerp(this.surface2, Color.Black, 0.55f) else lerp(this.surface2, Color.Black, 0.28f)
    val modTop = if (dark) lerp(this.surface, Color.White, 0.04f) else lerp(this.surface2, Color.Black, 0.08f)
    val fret = lerp(this.muted, this.deck, 0.35f)

    // Ink colours pushed until they reach 4.5:1 on the surface they sit on, so every theme stays legible.
    val accentInk = readable(this.accent, keyTop)
    val accentOnMod = readable(this.accent, modTop)
    val mutedInk = readable(this.muted, keyTop)
    val dangerInk = readable(this.danger, this.surface)
    val fieldMuted = readable(this.muted, this.surface2)
    val onPrimaryInk = readable(this.onPrimary, this.primary)
    /** Outline for inputs and cards; 3:1 against the fill they sit on. */
    val outline = readable(this.border, this.surface2, 3f)
}

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f; val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

internal fun readable(c: Color, on: Color, min: Float = 4.5f): Color {
    if (contrast(c, on) >= min) return c
    val target = if (on.luminance() > 0.4f) Color.Black else Color.White
    var t = 0f
    var out = c
    while (t < 1f && contrast(out, on) < min) { t += 0.05f; out = lerp(c, target, t) }
    return out
}

/** Spacing, radius and type scale. Components read these instead of literal sizes. */
object Tokens {
    val s1 = 4.dp; val s2 = 8.dp; val s3 = 12.dp; val s4 = 16.dp; val s5 = 24.dp
    val rKey = 7.dp; val rSm = 8.dp; val rMd = 12.dp; val rLg = 16.dp
    val touch = 44.dp; val bar = 52.dp; val fnRowMin = 36.dp
    val caption = 11.sp; val keySm = 12.sp; val label = 13.sp; val body = 15.sp; val legend = 17.sp; val title = 18.sp
}

val Mono = FontFamily(Font(R.font.jetbrains_mono), Font(R.font.jetbrains_mono_bold, FontWeight.Bold))

val LocalPalette = staticCompositionLocalOf { ThemePack.all.first() }

@Composable
fun DeckTheme(p: Palette, content: @Composable () -> Unit) {
    val scheme = if (p.dark) darkColorScheme(
        primary = p.primary, onPrimary = p.onPrimaryInk, secondary = p.accent, background = p.bg, onBackground = p.fg,
        surface = p.surface, onSurface = p.fg, surfaceVariant = p.surface2, onSurfaceVariant = p.muted,
        outline = p.border, error = p.danger, surfaceContainer = p.surface, surfaceContainerHigh = p.surface2,
        surfaceContainerLow = p.surface, surfaceContainerHighest = p.surface2,
        primaryContainer = p.primary.copy(alpha = 0.24f).compositeOver(p.surface2), onPrimaryContainer = p.fg,
        secondaryContainer = p.primary.copy(alpha = 0.24f).compositeOver(p.surface2), onSecondaryContainer = p.fg,
        onSecondary = p.onPrimary, tertiary = p.accent, surfaceTint = p.primary, outlineVariant = p.border, onError = p.onPrimary,
    ) else lightColorScheme(
        primary = p.primary, onPrimary = p.onPrimaryInk, secondary = p.accent, background = p.bg, onBackground = p.fg,
        surface = p.surface, onSurface = p.fg, surfaceVariant = p.surface2, onSurfaceVariant = p.muted,
        outline = p.border, error = p.danger, surfaceContainer = p.surface, surfaceContainerHigh = p.surface2,
        surfaceContainerLow = p.surface, surfaceContainerHighest = p.surface2,
        primaryContainer = p.primary.copy(alpha = 0.24f).compositeOver(p.surface2), onPrimaryContainer = p.fg,
        secondaryContainer = p.primary.copy(alpha = 0.24f).compositeOver(p.surface2), onSecondaryContainer = p.fg,
        onSecondary = p.onPrimary, tertiary = p.accent, surfaceTint = p.primary, outlineVariant = p.border, onError = p.onPrimary,
    )
    CompositionLocalProvider(LocalPalette provides p) { MaterialTheme(colorScheme = scheme, content = content) }
}
