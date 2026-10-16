package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Electric Ink: opaque blue-black panels, violet actions and a restrained citrus accent.
 * Keep colors explicit so wallpaper-derived colors cannot alter the default visual hierarchy.
 */
internal object ReadingAppColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFFA38AFF),
        onPrimary = Color(0xFF1D2040),
        primaryContainer = Color(0xFF3F315E),
        onPrimaryContainer = Color(0xFFE7DDFF),
        inversePrimary = Color(0xFF5C35B7),
        secondary = Color(0xFFA38AFF),
        onSecondary = Color(0xFF1D2040),
        secondaryContainer = Color(0xFF3F315E),
        onSecondaryContainer = Color(0xFFD7C6FF),
        tertiary = Color(0xFFDDF56D),
        onTertiary = Color(0xFF202608),
        tertiaryContainer = Color(0xFF38421B),
        onTertiaryContainer = Color(0xFFE5F2AD),
        background = Color(0xFF101529),
        onBackground = Color(0xFFF4F6FF),
        surface = Color(0xFF101529),
        onSurface = Color(0xFFF4F6FF),
        surfaceVariant = Color(0xFF1C2339),
        onSurfaceVariant = Color(0xFFA7AFC4),
        surfaceTint = Color(0xFFA38AFF),
        inverseSurface = Color(0xFFF4F3FA),
        inverseOnSurface = Color(0xFF151B2C),
        error = Color(0xFFFFB4B0),
        onError = Color(0xFF620A12),
        errorContainer = Color(0xFF82212B),
        onErrorContainer = Color(0xFFFFDAD8),
        outline = Color(0xFF8790A8),
        outlineVariant = Color(0xFF30384E),
        surfaceContainerLowest = Color(0xFF101529),
        surfaceContainerLow = Color(0xFF171E33),
        surfaceContainer = Color(0xFF1C2339),
        surfaceContainerHigh = Color(0xFF242C44),
        surfaceContainerHighest = Color(0xFF2C354E),
    )

    // A complete opaque light palette remains available for daylight and system-theme use.
    override val lightScheme = lightColorScheme(
        primary = Color(0xFF5C35B7),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE8DDFF),
        onPrimaryContainer = Color(0xFF29144D),
        inversePrimary = Color(0xFFAC91FF),
        secondary = Color(0xFF5C35B7),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE8DDFF),
        onSecondaryContainer = Color(0xFF29144D),
        tertiary = Color(0xFF50620C),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFE1EDAB),
        onTertiaryContainer = Color(0xFF253200),
        background = Color(0xFFF5F5FB),
        onBackground = Color(0xFF151B2C),
        surface = Color(0xFFF5F5FB),
        onSurface = Color(0xFF151B2C),
        surfaceVariant = Color(0xFFE5E7F1),
        onSurfaceVariant = Color(0xFF4B5369),
        surfaceTint = Color(0xFF5C35B7),
        inverseSurface = Color(0xFF20273A),
        inverseOnSurface = Color(0xFFF5F3FF),
        error = Color(0xFFAC2433),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD8),
        onErrorContainer = Color(0xFF44040B),
        outline = Color(0xFF6F7891),
        outlineVariant = Color(0xFFC7CDDF),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF0F1F8),
        surfaceContainer = Color(0xFFE9ECF5),
        surfaceContainerHigh = Color(0xFFE1E5F0),
        surfaceContainerHighest = Color(0xFFD9DEEB),
    )
}
