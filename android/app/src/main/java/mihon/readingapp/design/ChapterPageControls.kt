package mihon.readingapp.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.rounded.Search
import mihon.readingapp.library.ChapterPageWindow

@Composable
fun ChapterPageControls(
    window: ChapterPageWindow,
    totalItems: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    onPageChange: (Int) -> Unit,
    onCurrentPage: (() -> Unit)? = null,
    onNextUnreadPage: (() -> Unit)? = null,
    onFindRequested: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var findDialogOpen by remember { mutableStateOf(false) }
    var actionsExpanded by remember { mutableStateOf(false) }
    val hasPages = window.pageCount > 0
    val toolbarSurface = Ink.Panel
    val toolbarContent = inkReadableForeground(null, listOf(toolbarSurface, androidx.compose.material3.MaterialTheme.colorScheme.background))
    val pageDescription = when {
        hasPages -> buildString {
            append("Page ${window.pageIndex + 1} of ${window.pageCount}. ")
            append("Chapters ${window.startIndex + 1} to ${window.endIndexExclusive} of $totalItems. ")
            if (query.isNotBlank()) append("Search filter: $query. ")
            append("Find or jump to a chapter or search.")
        }
        query.isBlank() -> "No chapters. Find chapters or search."
        else -> "No matches for $query. Search or jump to a chapter."
    }
    val pageLabel = when {
        hasPages -> "Page ${window.pageIndex + 1}/${window.pageCount}"
        query.isBlank() -> "Find chapters"
        else -> "No matches"
    }
    val detailLabel = when {
        hasPages && query.isNotBlank() -> "Search: ${query.take(10)}"
        hasPages -> "${window.startIndex + 1}–${window.endIndexExclusive}/$totalItems"
        query.isNotBlank() -> "Search: ${query.take(10)}"
        else -> "No chapters"
    }
    val hasExtraActions = onCurrentPage != null || onNextUnreadPage != null
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        fun textWidth(text: String, style: TextStyle) =
            with(density) { textMeasurer.measure(text, style = style).size.width.toDp() }

        val pageColumnWidth = maxOf(
            textWidth(pageLabel, MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp)),
            textWidth(detailLabel, MaterialTheme.typography.labelLarge.copy(fontSize = 10.sp)),
        )
        val pageButtonWidth = maxOf(64.dp, pageColumnWidth + 24.dp + 24.dp + 4.dp)
        val currentButtonWidth = onCurrentPage?.let {
            maxOf(64.dp, textWidth("Current", MaterialTheme.typography.labelLarge) + 24.dp)
        } ?: 0.dp
        val unreadButtonWidth = onNextUnreadPage?.let {
            maxOf(64.dp, textWidth("Next unread", MaterialTheme.typography.labelLarge) + 24.dp)
        } ?: 0.dp
        val actionCount = listOfNotNull(onCurrentPage, onNextUnreadPage).size
        val requiredInlineWidth = 8.dp + 48.dp + pageButtonWidth + 48.dp + currentButtonWidth + unreadButtonWidth +
            (2.dp * (2 + actionCount).toFloat())
        val actionsFitInline = hasExtraActions && maxWidth >= requiredInlineWidth

        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                enabled = hasPages && window.pageIndex > 0,
                onClick = { onPageChange(window.pageIndex - 1) },
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = toolbarContent,
                    disabledContentColor = toolbarContent.copy(alpha = 0.38f),
                ),
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "Previous page" },
            ) {
                Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack, contentDescription = null)
            }
            TextButton(
                onClick = {
                    if (onFindRequested == null) findDialogOpen = true else onFindRequested()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = toolbarContent),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                    contentDescription = pageDescription
                },
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(pageLabel, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            detailLabel,
                            color = if (query.isBlank()) toolbarContent else inkReadableForeground(
                                Ink.Link,
                                listOf(toolbarSurface, androidx.compose.material3.MaterialTheme.colorScheme.background),
                            ),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        MaterialSymbols.Rounded.Search,
                        contentDescription = null,
                        tint = if (query.isBlank()) toolbarContent else inkReadableForeground(
                            Ink.Link,
                            listOf(toolbarSurface, androidx.compose.material3.MaterialTheme.colorScheme.background),
                        ),
                    )
                }
            }
            IconButton(
                enabled = hasPages && window.pageIndex + 1 < window.pageCount,
                onClick = { onPageChange(window.pageIndex + 1) },
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = toolbarContent,
                    disabledContentColor = toolbarContent.copy(alpha = 0.38f),
                ),
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "Next page" },
            ) {
                Icon(MaterialSymbols.AutoMirroredRounded.ArrowForward, contentDescription = null)
            }
            if (actionsFitInline) {
                onCurrentPage?.let { action ->
                    TextButton(
                        onClick = action,
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = toolbarContent),
                    ) { Text("Current", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                onNextUnreadPage?.let { action ->
                    TextButton(
                        onClick = action,
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = toolbarContent),
                    ) { Text("Next unread", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            } else if (hasExtraActions) {
                Box {
                    IconButton(
                        onClick = { actionsExpanded = true },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "More chapter actions" },
                        colors = IconButtonDefaults.iconButtonColors(contentColor = toolbarContent),
                    ) {
                        Icon(MaterialSymbols.Rounded.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = actionsExpanded,
                        onDismissRequest = { actionsExpanded = false },
                        containerColor = Ink.Raised,
                    ) {
                        onCurrentPage?.let { action ->
                            DropdownMenuItem(
                                text = { Text("Current", color = inkReadableForeground(Ink.Text, Ink.Raised)) },
                                onClick = { actionsExpanded = false; action() },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                        onNextUnreadPage?.let { action ->
                            DropdownMenuItem(
                                text = { Text("Next unread", color = inkReadableForeground(Ink.Text, Ink.Raised)) },
                                onClick = { actionsExpanded = false; action() },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    ChapterFindDialog(
        open = findDialogOpen,
        window = window,
        query = query,
        onQueryChange = onQueryChange,
        onPageChange = onPageChange,
        onDismiss = { findDialogOpen = false },
    )
}

@Composable
fun ChapterFindDialog(
    open: Boolean,
    window: ChapterPageWindow,
    query: String,
    onQueryChange: (String) -> Unit,
    onPageChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var requestedPage by remember(window.pageIndex, window.pageCount) {
        mutableStateOf(if (window.pageCount == 0) "" else (window.pageIndex + 1).toString())
    }
    if (open) {
        AlertDialog(
            onDismissRequest = onDismiss,
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
                    enabled = window.pageCount > 0 && requestedPage.toIntOrNull() != null,
                    onClick = {
                        onPageChange((requestedPage.toInt() - 1).coerceIn(0, window.pageCount - 1))
                        onDismiss()
                    },
                ) {
                    Text("Go")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            },
        )
    }
}
