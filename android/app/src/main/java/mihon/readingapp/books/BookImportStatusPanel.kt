package mihon.readingapp.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mihon.readingapp.design.Ink

@Composable
fun BookImportStatusPanel(
    state: BookImportState,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == BookImportState.Idle) return
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Ink.Raised)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state) {
            BookImportState.Idle -> Unit
            is BookImportState.Running -> {
                Text("IMPORTING ${state.fileIndex} OF ${state.fileCount}", color = Ink.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(state.fileName, color = Ink.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(state.stage, color = Ink.Muted, fontSize = 12.sp)
                val total = state.totalBytes
                val copied = state.bytesCopied
                if (total != null && total > 0 && copied != null) {
                    val fraction = (copied.toDouble() / total).toFloat().coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${(fraction * 100).toInt()}% · ${formatBytes(copied)} of ${formatBytes(total)}", color = Ink.Muted, fontSize = 11.sp)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Working… Keep Arkhive open or continue browsing; this panel will update here.", color = Ink.Muted, fontSize = 11.sp)
                }
            }
            is BookImportState.Complete -> {
                val duplicates = state.books.count { it.alreadyImported }
                val added = state.books.size - duplicates
                Text(
                    when {
                        state.books.isEmpty() -> "IMPORT FAILED"
                        duplicates > 0 && added == 0 -> "ALREADY IN YOUR LIBRARY"
                        state.failures.isEmpty() -> "IMPORT COMPLETE"
                        else -> "IMPORT FINISHED WITH ERRORS"
                    },
                    color = Ink.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                if (state.books.isNotEmpty()) {
                    Text(state.books.joinToString("\n") {it.title},color=Ink.Text,maxLines=3,overflow=TextOverflow.Ellipsis)
                    Text(buildString {
                        if (added > 0) append("$added added")
                        if (added > 0 && duplicates > 0) append(" · ")
                        if (duplicates > 0) append("$duplicates existing ${if (duplicates == 1) "book" else "books"} found by file fingerprint")
                    }, color = Ink.Muted, fontSize = 12.sp)
                }
                if (state.failures.isNotEmpty()) Text(state.failures.joinToString("\n"), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (state.failures.isNotEmpty()) TextButton(onClick = onRetry) { Text("Retry") }
                    if (state.books.isNotEmpty()) TextButton(onClick = { onOpen(state.books.last().id) }) {
                        val book = state.books.last()
                        Text(
                            when {
                                state.books.size > 1 -> "Open latest"
                                book.alreadyImported -> "Open existing book"
                                else -> "Open book"
                            },
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}
