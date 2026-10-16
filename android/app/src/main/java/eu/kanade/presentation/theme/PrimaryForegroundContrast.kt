package eu.kanade.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

internal fun ColorScheme.withAccessiblePrimaryForegrounds(): ColorScheme = copy(
    onPrimary = accessibleForeground(onPrimary, primary),
    onPrimaryContainer = accessibleForeground(onPrimaryContainer, primaryContainer),
)

internal fun accessibleForeground(
    preferred: Color,
    background: Color,
    minimumContrast: Float = 4.5f,
): Color {
    if (contrastRatio(preferred, background) >= minimumContrast) return preferred
    return if (contrastRatio(Color.White, background) > contrastRatio(Color.Black, background)) {
        Color.White
    } else {
        Color.Black
    }
}

internal fun contrastRatio(first: Color, second: Color): Float {
    val lighter = maxOf(first.luminance(), second.luminance())
    val darker = minOf(first.luminance(), second.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

internal fun prefersDarkSystemBarIcons(background: Color): Boolean =
    contrastRatio(Color.Black, background) >= contrastRatio(Color.White, background)
