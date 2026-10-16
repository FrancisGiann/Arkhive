package mihon.readingapp.design

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import eu.kanade.tachiyomi.R
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mihon.app.di.appGraph

/** App-scoped visual tokens backed by the selected app palette. */
object Ink {
    val Canvas: Color
        @Composable get() = MaterialTheme.colorScheme.background
    val Panel: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow
    val Raised: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh
    val Text: Color
        @Composable get() = MaterialTheme.colorScheme.onBackground
    val Muted: Color
        @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val Violet: Color
        @Composable get() = MaterialTheme.colorScheme.primary
    val Link: Color
        @Composable get() {
            val scheme = MaterialTheme.colorScheme
            val accent = scheme.primary
            val surface = scheme.background
            val lighter = maxOf(accent.luminance(), surface.luminance())
            val darker = minOf(accent.luminance(), surface.luminance())
            return if ((lighter + .05f) / (darker + .05f) >= 4.5f) accent else scheme.onBackground
        }
    val Citrus: Color
        @Composable get() = MaterialTheme.colorScheme.tertiary
    val OnViolet: Color
        @Composable get() = MaterialTheme.colorScheme.onPrimary
    val HeroSurface: Color
        @Composable get() = MaterialTheme.colorScheme.primaryContainer
    val HeroText: Color
        @Composable get() = MaterialTheme.colorScheme.onPrimaryContainer
    val HeroMuted: Color
        @Composable get() = MaterialTheme.colorScheme.onPrimaryContainer
    val Display = TextStyle(fontFamily = FontFamily(Font(R.font.ink_display,FontWeight.Black)),
        fontWeight = FontWeight.Black, fontSize = 34.sp, lineHeight = 37.sp, letterSpacing = (-.5).sp)
}

/** Pairs system-bar icon contrast with a detail hero and restores the host screen on exit. */
@Composable
fun InkStatusBarStyle(statusBarColor: Color) {
    val view = LocalView.current
    val context = LocalContext.current
    val activity = remember(context) {
        var current: Context = context
        while (current !is Activity && current is ContextWrapper) current = current.baseContext
        current as? Activity
    }
    val controller = remember(activity, view) {
        activity?.window?.let { WindowInsetsControllerCompat(it, view) }
    }
    val restoreDarkIcons = remember(controller) { controller?.isAppearanceLightStatusBars }
    DisposableEffect(controller) {
        onDispose {
            if (controller != null && restoreDarkIcons != null) {
                controller.isAppearanceLightStatusBars = restoreDarkIcons
            }
        }
    }
    SideEffect {
        controller?.isAppearanceLightStatusBars = statusBarColor.luminance() > 0.5f
    }
}

@Composable fun inkReducedMotion(): Boolean {
    val context = LocalContext.current
    val reduced by remember { context.appGraph.uiPreferences.arkhiveReducedMotion.changes() }.collectAsState(false)
    return reduced || Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}
@Composable fun Modifier.inkPress(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val down by interaction.collectIsPressedAsState()
    val reduced = inkReducedMotion()
    val scale by animateFloatAsState(if (down && !reduced) .985f else 1f, tween(if (reduced) 0 else 100), label = "Press feedback")
    return graphicsLayer { scaleX = scale; scaleY = scale }.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
}
@Composable fun Modifier.inkContextPress(onClick: () -> Unit, onLongClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val down by interaction.collectIsPressedAsState()
    val reduced = inkReducedMotion()
    val scale by animateFloatAsState(if (down && !reduced) .985f else 1f, tween(if (reduced) 0 else 100), label = "Press feedback")
    return graphicsLayer { scaleX = scale; scaleY = scale }.combinedClickable(
        interactionSource = interaction,
        indication = null,
        role = Role.Button,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}
@Composable
fun Modifier.inkSlashes(color: Color? = null): Modifier {
    val useHeroPattern = color == null
    val slashColor = color ?: MaterialTheme.colorScheme.onPrimary.copy(alpha = .16f)
    return drawBehind {
        if (useHeroPattern) {
            val unit=size.width/364f
            for(offset in listOf(0f,48f)) drawPath(androidx.compose.ui.graphics.Path().apply {
                moveTo((281f+offset)*unit,0f);lineTo((227f+offset)*unit,70f*unit)
                lineTo((265f+offset)*unit,70f*unit);lineTo((319f+offset)*unit,0f);close()
            },slashColor)
        } else {
            drawLine(slashColor,Offset(size.width*.76f,size.height*.35f),Offset(size.width*.96f,0f),1.dp.toPx())
            drawLine(slashColor,Offset(size.width*.84f,size.height*.35f),Offset(size.width*1.04f,0f),1.dp.toPx())
        }
    }
}

@Composable fun InkKicker(text: String, modifier: Modifier = Modifier, color: Color = Ink.Muted) = Text(text.uppercase(), modifier,
    color = color, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
@Composable fun InkHeading(text: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics { heading() }) {
        Text(text.uppercase(), color = Ink.Text, style = Ink.Display)
        Spacer(Modifier.height(6.dp)); Box(Modifier.width(48.dp).height(3.dp).background(Ink.Citrus))
    }
}
@Composable fun InkIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Ink.Raised).inkPress(onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = Ink.Text, modifier = Modifier.size(22.dp))
    }
}
@Composable fun InkPill(text: String, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(if (selected) Ink.Violet else Ink.Panel)
        .inkPress(onClick).semantics { stateDescription = if(selected) "Selected" else "Not selected" }.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (selected) Ink.OnViolet else Ink.Text, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}
