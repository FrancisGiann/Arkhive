package eu.kanade.presentation.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrimaryForegroundContrastTest {
    @Test
    fun lightCatppuccinPurpleUsesReadablePrimaryForegroundsWithoutChangingSurfaces() {
        val scheme = lightColorScheme(
            primary = Color(0xFF8839EF),
            onPrimary = Color(0xFFDCE0E8),
            primaryContainer = Color(0xFF8839EF),
            onPrimaryContainer = Color(0xFFDCE0E8),
            background = Color(0xFFE6E9EF),
            surface = Color(0xFFE6E9EF),
        )

        val corrected = scheme.withAccessiblePrimaryForegrounds()

        assertTrue(contrastRatio(corrected.onPrimary, corrected.primary) >= 4.5f)
        assertTrue(contrastRatio(corrected.onPrimaryContainer, corrected.primaryContainer) >= 4.5f)
        assertEquals(Color.White, corrected.onPrimary)
        assertEquals(Color.White, corrected.onPrimaryContainer)
        assertEquals(scheme.primary, corrected.primary)
        assertEquals(scheme.primaryContainer, corrected.primaryContainer)
        assertEquals(scheme.background, corrected.background)
        assertEquals(scheme.surface, corrected.surface)
        assertEquals(scheme.onSurface, corrected.onSurface)
    }

    @Test
    fun alreadyReadableForegroundIsPreserved() {
        val scheme = darkColorScheme(
            primary = Color(0xFFCBA6F7),
            onPrimary = Color(0xFF11111B),
            primaryContainer = Color(0xFFCBA6F7),
            onPrimaryContainer = Color(0xFF11111B),
        )

        val corrected = scheme.withAccessiblePrimaryForegrounds()

        assertEquals(Color(0xFF11111B), corrected.onPrimary)
        assertEquals(Color(0xFF11111B), corrected.onPrimaryContainer)
    }

    @Test
    fun lightForegroundOnNearWhiteFallsBackToBlack() {
        val scheme = lightColorScheme(
            primary = Color(0xFFEFF1F5),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFEFF1F5),
            onPrimaryContainer = Color.White,
        )

        val corrected = scheme.withAccessiblePrimaryForegrounds()

        assertEquals(Color.Black, corrected.onPrimary)
        assertEquals(Color.Black, corrected.onPrimaryContainer)
        assertTrue(contrastRatio(corrected.onPrimary, corrected.primary) >= 4.5f)
        assertTrue(contrastRatio(corrected.onPrimaryContainer, corrected.primaryContainer) >= 4.5f)
    }

    @Test
    fun systemBarIconsUseTheHigherContrastColor() {
        assertTrue(prefersDarkSystemBarIcons(Color(0xFFCBA6F7)))
        assertTrue(!prefersDarkSystemBarIcons(Color(0xFF8839EF)))
    }
}
