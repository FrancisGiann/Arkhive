package eu.kanade.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import eu.kanade.tachiyomi.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Platform font rendering and scalable text; no runtime font download or custom rasterization. */
internal val readingAppTypography = Typography().let { base ->
    val body = FontFamily(Font(R.font.ink_body))
    val display = FontFamily(Font(R.font.ink_display, FontWeight.Black))
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = display, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp),
        displayMedium = base.displayMedium.copy(fontFamily = display, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp),
        displaySmall = base.displaySmall.copy(fontFamily = display, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp),
        headlineLarge = base.headlineLarge.copy(fontFamily = display, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp),
        headlineMedium = base.headlineMedium.copy(fontFamily = display, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.copy(fontFamily = display, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        titleLarge = base.titleLarge.copy(fontFamily = body, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleMedium = base.titleMedium.copy(fontFamily = body, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = body, fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontFamily = body, fontWeight = FontWeight.Bold),
        bodyLarge = base.bodyLarge.copy(fontFamily = body),
        bodyMedium = base.bodyMedium.copy(fontFamily = body),
        bodySmall = base.bodySmall.copy(fontFamily = body),
        labelSmall = base.labelSmall.copy(fontFamily = body),
        labelMedium = base.labelMedium.copy(fontFamily = body, fontWeight = FontWeight.SemiBold),
    )
}

/** Compact opaque panels; cover art supplies the richness instead of shadows or blur layers. */
internal val readingAppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
