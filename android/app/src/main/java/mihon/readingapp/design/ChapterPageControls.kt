package mihon.readingapp.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import mihon.icons.materialsymbols.rounded.Search
import mihon.readingapp.library.ChapterPageWindow

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChapterPageControls(
    window: ChapterPageWindow,
    totalItems: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    onPageChange: (Int) -> Unit,
    onCurrentPage: (() -> Unit)? = null,
    onNextUnreadPage: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var requestedPage by remember(window.pageIndex, window.pageCount) {
        mutableStateOf(if (window.pageCount == 0) "" else (window.pageIndex + 1).toString())
    }
    var findDialogOpen by remember { mutableStateOf(false) }
    val hasPages = window.pageCount > 0
    val cardSurface = Ink.Raised
    val cardContent = readableForeground(cardSurface)

    CompositionLocalProvider(LocalContentColor provides cardContent) {
        Column(
            modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(cardSurface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text = if (hasPages) {
                        "${window.startIndex + 1}–${window.endIndexExclusive} of $totalItems"
                    } else {
                        "No matching chapters"
                    },
                    color = cardContent,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
                )
                if (query.isNotBlank()) {
                    Text(
                        "Search: ${query.take(12)}",
                        color = cardContent,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                IconButton(
                    enabled = hasPages && window.pageIndex > 0,
                    onClick = { onPageChange(window.pageIndex - 1) },
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = cardContent,
                        disabledContentColor = cardContent.copy(alpha = 0.38f),
                    ),
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack, contentDescription = "Previous page")
                }
                TextButton(
                    onClick = { findDialogOpen = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = cardContent),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = if (hasPages) {
                                "Page ${window.pageIndex + 1} of ${window.pageCount}. Find or jump to a chapter or search."
                            } else if (query.isBlank()) {
                                "No chapters. Find chapters or search."
                            } else {
                                "No matches. Search or jump to a chapter."
                            }
                        },
                ) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            if (hasPages) "Page ${window.pageIndex + 1} of ${window.pageCount}"
                            else if (query.isBlank()) "Find chapters" else "No matches · Search",
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(MaterialSymbols.Rounded.Search, contentDescription = null, tint = cardContent)
                    }
                }
                IconButton(
                    enabled = hasPages && window.pageIndex + 1 < window.pageCount,
                    onClick = { onPageChange(window.pageIndex + 1) },
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = cardContent,
                        disabledContentColor = cardContent.copy(alpha = 0.38f),
                    ),
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Icon(MaterialSymbols.AutoMirroredRounded.ArrowForward, contentDescription = "Next page")
                }
            }

            if (onCurrentPage != null || onNextUnreadPage != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    onCurrentPage?.let { action ->
                        TextButton(
                            onClick = action,
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = cardContent),
                        ) {
                            Text("Current")
                        }
                    }
                    onNextUnreadPage?.let { action ->
                        TextButton(
                            onClick = action,
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = cardContent),
                        ) {
                            Text("Next unread")
                        }
                    }
                }
            }
        }
    }

    if (findDialogOpen) {
        AlertDialog(
            onDismissRequest = { findDialogOpen = false },
            title = { Text("Find or jump to chapter") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Title or chapter number") },
                    )
                    OutlinedTextField(
                        value = requestedPage,
                        onValueChange = { requestedPage = it.filter(Char::isDigit).take(10) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Go to page") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = hasPages && requestedPage.toIntOrNull() != null,
                    onClick = {
                        onPageChange((requestedPage.toInt() - 1).coerceIn(0, window.pageCount - 1))
                        findDialogOpen = false
                    },
                ) {
                    Text("Go")
                }
            },
            dismissButton = {
                TextButton(onClick = { findDialogOpen = false }) {
                    Text("Done")
                }
            },
        )
    }
}

private fun readableForeground(surface: Color): Color {
    fun contrast(color: Color): Float {
        val light = maxOf(color.luminance(), surface.luminance())
        val dark = minOf(color.luminance(), surface.luminance())
        return (light + 0.05f) / (dark + 0.05f)
    }

    return if (contrast(Color.White) >= contrast(Color.Black)) Color.White else Color.Black
}
