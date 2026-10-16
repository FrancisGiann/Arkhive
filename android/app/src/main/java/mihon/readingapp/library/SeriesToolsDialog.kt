package mihon.readingapp.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.FlowRow
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.readingapp.design.Ink

/** One themed, accessible action-row treatment for every series tools surface. */
data class SeriesToolAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
fun SeriesToolsDialog(
    actions: List<SeriesToolAction>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Series tools", color = Ink.Text) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                actions.forEach { action ->
                    val shape = RoundedCornerShape(14.dp)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape)
                            .background(Ink.Raised)
                            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
                            .clickable {
                                onDismiss()
                                action.onClick()
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(action.icon, contentDescription = null, tint = Ink.Link, modifier = Modifier.size(21.dp))
                        Text(action.label, color = Ink.Text, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}


@Composable
fun SeriesSynopsisTagsDialog(
    synopsis: String?,
    tags: List<String>,
    additionalInfo: String? = null,
    onDismiss: () -> Unit,
) {
    val hasSynopsis = !synopsis.isNullOrBlank()
    val cleanTags = tags.map(String::trim).filter(String::isNotEmpty).distinct()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Synopsis & tags", color = Ink.Text) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasSynopsis) Text(synopsis!!.trim(), color = Ink.Text, fontSize = 14.sp, lineHeight = 21.sp)
                if (cleanTags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    cleanTags.forEach { tag ->
                        Text(tag, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Ink.Raised)
                            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp), color = Ink.Text, fontSize = 12.sp)
                    }
                }
                if (!hasSynopsis && cleanTags.isEmpty()) Text("No synopsis or tags are available for this title.", color = Ink.Muted, fontSize = 13.sp)
                additionalInfo?.takeIf(String::isNotBlank)?.let { Text(it, color = Ink.Muted, fontSize = 12.sp, lineHeight = 18.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Add-only picker. It stages new shelf names and additions locally; nothing is written until Save.
 * Existing per-title memberships and statuses are preserved by LibraryToolsStore.addToShelves.
 */
@Composable
fun AddToShelvesDialog(
    keys: Set<String>,
    store: LibraryToolsStore,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = onDismiss,
) {
    val state by store.state.collectAsState()
    val existingByKey = remember(keys, state.assignments) { keys.associateWith { state.assignments[it]?.shelves.orEmpty() } }
    val existingCounts = remember(keys, state.assignments) {
        (existingByKey.values.flatten()).distinct().associateWith { shelf -> existingByKey.values.count { shelf in it } }
    }
    val existingOnAll = remember(keys, existingCounts) {
        existingCounts.filterValues { it == keys.size }.keys
    }
    var additions by remember(keys) { mutableStateOf(emptySet<String>()) }
    var draftShelves by remember(keys) { mutableStateOf(emptySet<String>()) }
    var shelfName by remember(keys) { mutableStateOf("") }
    var validationMessage by remember(keys) { mutableStateOf<String?>(null) }
    val shelves = remember(state.shelves, draftShelves) { (state.shelves + draftShelves).distinct() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (keys.size == 1) "Add to shelf" else "Add to shelves", color = Ink.Text) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (keys.size == 1) "Current memberships stay in place."
                    else "Current memberships stay on each selected story.",
                    color = Ink.Muted,
                    fontSize = 12.sp,
                )
                if (shelves.isEmpty()) Text("No shelves yet. Create one below.", color = Ink.Muted, fontSize = 12.sp)
                shelves.forEach { shelf ->
                    val count = existingCounts[shelf] ?: 0
                    val alreadyOnAll = shelf in existingOnAll
                    val checked = alreadyOnAll || shelf in additions
                    val isDraft = shelf in draftShelves
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !alreadyOnAll) {
                                additions = if (checked) additions - shelf else additions + shelf
                            }
                            .padding(start = 4.dp, end = if (isDraft) 0.dp else 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            enabled = !alreadyOnAll,
                            onCheckedChange = { selected -> additions = if (selected) additions + shelf else additions - shelf },
                        )
                        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                            Text(shelf, color = Ink.Text, fontSize = 13.sp)
                            val status = when {
                                isDraft -> "Created when saved"
                                alreadyOnAll -> "Already on all selected stories"
                                count > 0 -> if (checked) "Save adds it to all" else "Already on $count of ${keys.size}; select to add it to all"
                                else -> null
                            }
                            status?.let { Text(it, color = Ink.Muted, fontSize = 10.sp, lineHeight = 13.sp) }
                        }
                        if (isDraft) IconButton(onClick = {
                            draftShelves = draftShelves - shelf
                            additions = additions - shelf
                        }) { Icon(MaterialSymbols.Rounded.Close, "Remove draft shelf", tint = Ink.Muted) }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = shelfName,
                        onValueChange = { shelfName = it.take(80); validationMessage = null },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("New shelf") },
                        singleLine = true,
                    )
                    TextButton(
                        onClick = {
                            val clean = shelfName.trim().take(80)
                            if (clean.isNotEmpty()) {
                                if (clean !in state.shelves && clean !in draftShelves && state.shelves.size + draftShelves.size >= 100) {
                                    validationMessage = "You can create up to 100 shelves."
                                } else {
                                    if (clean !in state.shelves) draftShelves = draftShelves + clean
                                    additions = additions + clean
                                    shelfName = ""
                                }
                            }
                        },
                        enabled = shelfName.isNotBlank(),
                    ) { Text("Add") }
                }
                validationMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = keys.isNotEmpty() && additions.isNotEmpty(),
                onClick = {
                    runCatching { store.addToShelves(keys, additions) }
                        .onSuccess { onSaved() }
                        .onFailure { validationMessage = it.message ?: "The shelf could not be saved." }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
