package mihon.readingapp.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = if (hasPages) {
                    "${window.startIndex + 1}–${window.endIndexExclusive} of $totalItems"
                } else {
                    "No matching chapters"
                },
                color = Ink.Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp).weight(1f),
            )
            TextButton(onClick = { findDialogOpen = true }, modifier = Modifier.heightIn(min = 40.dp)) {
                Text(if (query.isBlank()) "Find / jump" else "Find · ${query.take(12)}")
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.Center,
        ) {
            TextButton(enabled = hasPages && window.pageIndex > 0, onClick = { onPageChange(0) }, modifier = Modifier.heightIn(min = 40.dp)) { Text("First") }
            TextButton(enabled = hasPages && window.pageIndex > 0, onClick = { onPageChange(window.pageIndex - 1) }, modifier = Modifier.heightIn(min = 40.dp)) { Text("Prev") }
            Text("${if (hasPages) window.pageIndex + 1 else 0} / ${window.pageCount}", color = Ink.Text, modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp))
            TextButton(enabled = hasPages && window.pageIndex + 1 < window.pageCount, onClick = { onPageChange(window.pageIndex + 1) }, modifier = Modifier.heightIn(min = 40.dp)) { Text("Next") }
            TextButton(enabled = hasPages && window.pageIndex + 1 < window.pageCount, onClick = { onPageChange(window.pageCount - 1) }, modifier = Modifier.heightIn(min = 40.dp)) { Text("Last") }
        }
        if (onCurrentPage != null || onNextUnreadPage != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                onCurrentPage?.let { action ->
                    TextButton(onClick = action, modifier = Modifier.heightIn(min = 40.dp)) { Text("Current") }
                }
                onNextUnreadPage?.let { action ->
                    TextButton(onClick = action, modifier = Modifier.heightIn(min = 40.dp)) { Text("Next unread") }
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
                        onValueChange = { requestedPage = it.filter(Char::isDigit).take(6) },
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
                ) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { findDialogOpen = false }) { Text("Done") } },
        )
    }
}
