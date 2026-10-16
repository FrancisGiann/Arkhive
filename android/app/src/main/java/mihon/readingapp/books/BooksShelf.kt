package mihon.readingapp.books

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.domain.book.LocalBookFormat
import mihon.domain.book.LocalBook

@Composable
fun BooksShelf() {
    val context = LocalContext.current
    val graph = context.appGraph
    val allBooks by remember { graph.localBookRepository.observeBooks() }.collectAsStateWithLifecycle(emptyList())
    val hidden by graph.libraryVisibility.hiddenSeries.changes().collectAsStateWithLifecycle(graph.libraryVisibility.hiddenSeries.get())
    val removed by graph.libraryShelfMembership.removedBookIds.collectAsStateWithLifecycle()
    val books=allBooks.filterNot {"book:${it.id}" in hidden || it.id in removed}
    val importer = graph.localBookImporter
    val scope = rememberCoroutineScope()
    val importState by graph.bookImportCoordinator.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var feedback by remember { mutableStateOf<String?>(null) }
    var selectedBook by remember { mutableStateOf<LocalBook?>(null) }
    var rename by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var editedTitle by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && !graph.bookImportCoordinator.start(uris)) feedback = "An import is already running. Its progress is shown below."
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Books & PDFs", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() })
            Button(enabled = importState !is BookImportState.Running, onClick = {
                try { picker.launch(arrayOf("application/epub+zip", "application/pdf", "text/plain")) }
                catch (_: ActivityNotFoundException) { feedback = "Android's file picker is unavailable on this device." }
            }) { Text(if (importState is BookImportState.Running) "Importing…" else "Import") }
        }
        Text("Private offline copies · EPUB, PDF and novel text", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        BookImportStatusPanel(
            state = importState,
            onOpen = { id -> context.startActivity(Intent(context, BookReaderActivity::class.java).putExtra(BookReaderActivity.BOOK_ID, id)) },
            onRetry = { graph.bookImportCoordinator.retry() },
            onDismiss = { graph.bookImportCoordinator.dismiss() },
            modifier = Modifier.padding(vertical = 8.dp),
        )
        OutlinedTextField(query, { query = it }, label = { Text("Search title or author") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("All", "EPUB", "PDF", "Text").forEach { name ->
                FilterChip(selected = filter == name, onClick = { filter = name }, label = { Text(name) })
            }
        }
        val visible = books.filter { (filter == "All" || it.format.name.equals(filter, true)) &&
            (query.isBlank() || it.title.contains(query, true) || it.author?.contains(query, true) == true) }
        if (visible.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Text(if (books.isEmpty()) "Bring a book. Keep your place.\nImport your local EPUB, PDF or text file."
                else "No matching books", style = MaterialTheme.typography.bodyLarge)
        } else LazyVerticalGrid(GridCells.Adaptive(145.dp), Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(visible, key = { it.id }) { book ->
                Card(Modifier.fillMaxWidth().combinedClickable(onClick = {
                    context.startActivity(Intent(context, BookReaderActivity::class.java).putExtra(BookReaderActivity.BOOK_ID, book.id))
                }, onLongClick = { selectedBook = book; editedTitle = book.title })) {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.68f).background(MaterialTheme.colorScheme.primaryContainer)) {
                        val cover = importer.coverFile(book)
                        if (cover != null) AsyncImage(cover, "Cover for ${book.title}", contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize())
                        else Text(book.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            maxLines = 5, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Surface(Modifier.align(Alignment.TopStart).padding(8.dp), shape = MaterialTheme.shapes.small) {
                            Text(book.format.name, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(book.title, Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp), maxLines = 2,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(book.author ?: "Local file", Modifier.padding(horizontal = 10.dp), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(progress = { book.progression.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(10.dp))
                    Text(if (book.locator == null) "Start reading" else "Resume · ${(book.progression * 100).toInt()}%",
                        Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    feedback?.let { message -> AlertDialog(onDismissRequest = { feedback = null }, title = { Text("Import") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { feedback = null }) { Text("Done") } }) }
    selectedBook?.let { book ->
        when {
            rename -> AlertDialog(onDismissRequest = { rename = false; selectedBook = null }, title = { Text("Rename book") },
                text = { OutlinedTextField(editedTitle, { editedTitle = it.take(500) }, label = { Text("Title") }) },
                confirmButton = { TextButton(enabled = editedTitle.isNotBlank(), onClick = {
                    scope.launch { graph.localBookRepository.rename(book.id, editedTitle); rename = false; selectedBook = null }
                }) { Text("Save") } }, dismissButton = { TextButton(onClick = { rename = false; selectedBook = null }) { Text("Cancel") } })
            remove -> AlertDialog(onDismissRequest = { remove = false; selectedBook = null }, title = { Text("Delete Arkhive's copy of ${book.title}?") },
                text = { Text("Arkhive's private reading copy will be deleted and the item removed from your shelf. Progress and bookmarks are kept, and your originally selected document stays where it is.") },
                confirmButton = { TextButton(onClick = {
                    scope.launch {
                        importer.deletePrivateAssets(book.id)
                        graph.libraryShelfMembership.removeBook(book.id)
                        remove = false; selectedBook = null
                    }
                }) { Text("Delete copy") } }, dismissButton = { TextButton(onClick = { remove = false; selectedBook = null }) { Text("Cancel") } })
            else -> AlertDialog(onDismissRequest = { selectedBook = null }, title = { Text(book.title) },
                text = { Column { TextButton(onClick = { rename = true }) { Text("Rename") }
                    TextButton(onClick = {
                        graph.libraryShelfMembership.removeBook(book.id)
                        selectedBook = null
                        feedback = "Removed from Library. Progress, bookmarks and Arkhive's private copy were kept."
                    }) { Text("Remove from library") }
                    TextButton(onClick = { remove = true }) { Text("Delete private copy") } } },
                confirmButton = { TextButton(onClick = { selectedBook = null }) { Text("Close") } })
        }
    }
}

@Composable
fun ArkhiveLibraryContent() {
    var booksSelected by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!booksSelected, { booksSelected = false }, label = { Text("Manga & comics") })
            FilterChip(booksSelected, { booksSelected = true }, label = { Text("Books & PDFs") })
        }
        Box(Modifier.weight(1f)) {
            if (booksSelected) BooksShelf() else eu.kanade.tachiyomi.ui.library.LibraryTab.Content()
        }
    }
}
