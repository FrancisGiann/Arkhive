package mihon.readingapp.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.CollectionsBookmark
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.roundedfilled.Visibility
import mihon.icons.materialsymbols.roundedfilled.VisibilityOff

/** Shared source-series hero geometry for manga and novel detail pages. */
@Composable
fun InkSeriesDetailHero(
    title: String,
    sourceKicker: String,
    author: String?,
    source: String?,
    metadata: List<String> = emptyList(),
    topPadding: Dp = 16.dp,
    bottomPadding: Dp = 20.dp,
    modifier: Modifier = Modifier,
    onTitleClick: (() -> Unit)? = null,
    cover: @Composable (Modifier) -> Unit,
) {
    val heroSurface = Ink.HeroSurface
    val slashColor = Ink.Violet.copy(alpha = .1f)
    val heroContent = inkReadableForeground(
        Ink.HeroText,
        listOf(heroSurface, slashColor.compositeOver(heroSurface)),
    )
    BoxWithConstraints(
        modifier.fillMaxWidth()
            .background(heroSurface)
            .inkSlashes(slashColor)
            .padding(start = 16.dp, top = topPadding, end = 16.dp, bottom = bottomPadding),
    ) {
        val coverWidth = when {
            maxWidth < 280.dp -> 68.dp
            maxWidth < 340.dp -> 78.dp
            else -> 94.dp
        }
        val titleFontSize = when {
            maxWidth < 280.dp -> 21.sp
            maxWidth < 360.dp -> 23.sp
            else -> 26.sp
        }
        val titleLineHeight = when {
            maxWidth < 280.dp -> 25.sp
            maxWidth < 360.dp -> 27.sp
            else -> 30.sp
        }
        val coverTitleSpacing = if (maxWidth < 280.dp) 12.dp else 18.dp
        val stackedTitle = maxWidth < 320.dp || (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.2f && maxWidth < 400.dp)
        val titleModifier = Modifier.fillMaxWidth().semantics { contentDescription = title }
        val clickableTitleModifier = onTitleClick?.let { titleModifier.clickable(role = Role.Button, onClick = it) } ?: titleModifier

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(coverTitleSpacing),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.width(coverWidth).aspectRatio(.68f)) {
                    cover(Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InkKicker(sourceKicker, color = heroContent)
                    if (!stackedTitle) {
                        Text(
                            text = title,
                            style = Ink.Display.copy(fontSize = titleFontSize, lineHeight = titleLineHeight),
                            color = heroContent,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = clickableTitleModifier,
                        )
                    }
                    author?.takeIf(String::isNotBlank)?.let {
                        Text(it, color = heroContent, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    source?.takeIf(String::isNotBlank)?.let {
                        Text(it, color = heroContent, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    metadata.filter(String::isNotBlank).forEach {
                        Text(it, color = heroContent, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (stackedTitle) {
                Text(
                    text = title,
                    style = Ink.Display.copy(fontSize = titleFontSize, lineHeight = titleLineHeight),
                    color = heroContent,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = clickableTitleModifier,
                )
            }
        }
    }
}

/** Compact shared add/remove-from-library and More row used by manga and novel details. */
@Composable
fun InkSeriesLibraryActions(
    inLibrary: Boolean,
    onLibraryClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = Ink.Text,
) {
    val safeContentColor = inkReadableForeground(contentColor, Ink.HeroSurface)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onLibraryClick,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = safeContentColor),
        ) {
            Text(if (inLibrary) "✓  In library" else "+  Add to library", color = safeContentColor, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(
            onClick = onMoreClick,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = safeContentColor),
        ) {
            Text("More", color = safeContentColor, fontSize = 11.sp)
        }
    }
}

/** Shared title/count row for manga and novel chapter sections. */
@Composable
fun InkDetailSectionHeading(
    title: String,
    count: String?,
    modifier: Modifier = Modifier,
    surface: Color = MaterialTheme.colorScheme.background,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title.uppercase(),
            Modifier.weight(1f),
            style = Ink.Display.copy(fontSize = 21.sp, lineHeight = 26.sp),
            color = inkReadableForeground(Ink.Text, surface),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        count?.takeIf(String::isNotBlank)?.let {
            Text(it, color = inkReadableForeground(Ink.Muted, surface), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/** Primary read action shared by manga and novel detail pages. */
@Composable
fun InkDetailPrimaryReadButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
) {
    val buttonSurface = if (enabled) Ink.Violet else Ink.Raised
    val buttonContent = inkReadableForeground(if (enabled) Ink.OnViolet else Ink.Muted, buttonSurface)
    Box(
        modifier.fillMaxWidth().padding(horizontal = horizontalPadding).heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(buttonSurface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = buttonContent,
            fontSize = 11.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Compact detail toolbar; title is optional until the hero scrolls out of view. */
@Composable
fun InkCompactDetailToolbar(
    title: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val toolbarContent = inkReadableForeground(Ink.HeroText, Ink.HeroSurface)
    Row(
        modifier.fillMaxWidth().background(Ink.HeroSurface).statusBarsPadding().heightIn(min = 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack, contentDescription = "Back", tint = toolbarContent)
        }
        if (title.isNullOrBlank()) {
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        } else {
            Text(
                title,
                Modifier.weight(1f).semantics { contentDescription = title },
                color = toolbarContent,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        actions()
    }
}

/** Nonmodal library selection surface that keeps every action readable at narrow widths. */
@Composable
fun InkLibrarySelectionActions(
    selectedCount: Int,
    hiddenVisit: Boolean,
    showSeriesOptions: Boolean,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    onMeasuredHeight: (Int) -> Unit = {},
    onClose: () -> Unit,
    onAddToShelf: () -> Unit,
    onOrganize: () -> Unit,
    onSeriesOptions: () -> Unit,
    onToggleHidden: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onDeleteCopies: () -> Unit,
) {
    Surface(
        modifier = modifier.onSizeChanged { onMeasuredHeight(it.height) },
        shape = RoundedCornerShape(18.dp),
        color = Ink.Panel,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(MaterialSymbols.Rounded.Close, contentDescription = "Close selection", tint = inkReadableForeground(Ink.Text, Ink.Panel))
                }
                Text("$selectedCount selected", Modifier.weight(1f), color = inkReadableForeground(Ink.Text, Ink.Panel), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = (maxHeight - 68.dp).coerceAtLeast(0.dp))
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SelectionAction("Add to shelf", MaterialSymbols.Rounded.LocalLibrary, onAddToShelf)
                SelectionAction("Organize", MaterialSymbols.Rounded.CollectionsBookmark, onOrganize)
                if (showSeriesOptions) SelectionAction("Series options", MaterialSymbols.Rounded.Settings, onSeriesOptions)
                SelectionAction(
                    if (hiddenVisit) "Unhide" else "Hide",
                    if (hiddenVisit) MaterialSymbols.RoundedFilled.Visibility else MaterialSymbols.RoundedFilled.VisibilityOff,
                    onToggleHidden,
                )
                androidx.compose.material3.HorizontalDivider(color = Ink.Raised, modifier = Modifier.padding(vertical = 4.dp))
                Text("Library cleanup", color = inkReadableForeground(Ink.Muted, Ink.Panel), fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                SelectionAction("Remove from library", MaterialSymbols.Rounded.Close, onRemoveFromLibrary)
                SelectionAction("Delete copies", MaterialSymbols.Rounded.Delete, onDeleteCopies, destructive = true)
            }
        }
    }
}

@Composable
private fun SelectionAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val shape = RoundedCornerShape(12.dp)
    val background = if (destructive) MaterialTheme.colorScheme.errorContainer else Ink.Raised
    val foreground = inkReadableForeground(
        if (destructive) MaterialTheme.colorScheme.onErrorContainer else Ink.Text,
        background,
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = foreground)
        Text(label, color = foreground, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

fun inkReadableForeground(preferred: Color?, surface: Color): Color =
    inkReadableForeground(preferred, listOf(surface))

fun inkReadableForeground(preferred: Color?, surfaces: List<Color>): Color {
    if (surfaces.isEmpty()) return requireNotNull(preferred)

    fun contrast(foreground: Color, surface: Color): Float {
        val visibleForeground = foreground.compositeOver(surface)
        val light = maxOf(visibleForeground.luminance(), surface.luminance())
        val dark = minOf(visibleForeground.luminance(), surface.luminance())
        return (light + .05f) / (dark + .05f)
    }

    if (preferred != null && surfaces.all { contrast(preferred, it) >= 4.5f }) return preferred
    val whiteContrast = surfaces.minOf { contrast(Color.White, it) }
    val blackContrast = surfaces.minOf { contrast(Color.Black, it) }
    return if (whiteContrast >= blackContrast) Color.White else Color.Black
}
